package com.zszc.voicepipeline.sdk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 统一的麦克风 PCM 采集器。
 *
 * 唤醒和实时转写都需要 16k/16bit/mono PCM，本类集中处理 AudioRecord 生命周期，
 * 调用方只关心收到的 ByteArray。
 */
internal class PcmRecorder {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    /** 开始采集麦克风；每 1280 字节约等于 40ms 音频，适合流式写入 SDK。 */
    fun start(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        onPcm: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!recording.compareAndSet(false, true)) return
        val bufferSize = max(
            1280,
            AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
        )
        val recorder = try {
            AudioRecord(
                audioSource,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: SecurityException) {
            recording.set(false)
            onError(error)
            return
        }
        audioRecord = recorder
        worker = Thread({
            try {
                recorder.startRecording()
                while (recording.get()) {
                    val data = ByteArray(1280)
                    val size = recorder.read(data, 0, data.size)
                    // AudioRecord 可能返回小于目标帧长的数据，传给 SDK 前裁掉无效尾部。
                    if (size > 0) onPcm(if (size == data.size) data else data.copyOf(size))
                }
            } catch (error: Throwable) {
                if (recording.get()) onError(error)
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }, "assistant-mic").also { it.start() }
    }

    /** 标记停止并尝试唤醒 read 循环；最终 release 在采集线程 finally 中执行。 */
    fun stop() {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        audioRecord = null
        worker = null
    }
}
