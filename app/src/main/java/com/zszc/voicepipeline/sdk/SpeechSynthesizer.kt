package com.zszc.voicepipeline.sdk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.iflytek.aikit.core.AeeEvent
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiInput
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiText
import java.util.concurrent.Executors

/**
 * AIKit Aisound 离线语音合成封装。
 *
 * SDK 会分片回调 PCM 数据。这里不再在 SDK 回调线程里直接写 AudioTrack，
 * 而是把 PCM 放到独立播放线程，避免 AudioTrack 阻塞时拖住 AIKit 输出队列。
 */
class SpeechSynthesizer(private val onError: (String) -> Unit) {
    private val helper = AiHelper.getInst()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val playbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RobotTTS-Playback").apply { isDaemon = true }
    }

    private var handle: AiHandle? = null
    private var activeContext: SpeechContext? = null
    private var activeHandleId = INVALID_HANDLE_ID
    private var activeHandleI = INVALID_HANDLE_ID
    private var audioTrack: AudioTrack? = null
    private var completion: (() -> Unit)? = null
    private var finishRunnable: Runnable? = null
    private var queuedAudioBytes = 0L
    private var lastFinishedAtMs = 0L
    private var generation = 0
    private var audioChunkCount = 0
    private var synthesisEnded = false
    private var released = false

    init {
        helper.registerListener(ABILITY_ID, object : AiListener {
            override fun onResult(id: Int, output: MutableList<AiResponse>?, context: Any?) {
                val speechContext = currentContextOrNull(id, context) ?: return
                output.orEmpty()
                    .filter { it.key == "audio" }
                    .forEach { response ->
                        response.value
                            ?.takeIf { it.isNotEmpty() }
                            ?.copyOf()
                            ?.let { bytes -> enqueuePcm(speechContext, bytes) }
                    }
            }

            override fun onEvent(id: Int, event: Int, data: MutableList<AiResponse>?, context: Any?) {
                val speechContext = currentContextOrNull(id, context) ?: return
                if (event == AeeEvent.AEE_EVENT_END.value) {
                    scheduleFinishAfterPlaybackDrains(speechContext)
                }
            }

            override fun onError(id: Int, error: Int, message: String?, context: Any?) {
                val speechContext = currentContextOrNull(id, context) ?: return
                onError("语音合成错误 $error：${message.orEmpty()}")
                finish(speechContext, notify = false)
            }
        })
    }

    /** 合成并播放一段文本；调用前会停止上一次未完成的播报。 */
    fun speak(text: String, onComplete: () -> Unit): Result<Unit> = runCatching {
        stop()
        if (released) error("语音合成器已释放")

        val speechContext: SpeechContext
        val warmupMs: Int
        val track = newAudioTrack()
        synchronized(lock) {
            generation += 1
            speechContext = SpeechContext(generation)
            activeContext = speechContext
            activeHandleId = INVALID_HANDLE_ID
            activeHandleI = INVALID_HANDLE_ID
            audioTrack = track
            completion = onComplete
            queuedAudioBytes = 0L
            audioChunkCount = 0
            synthesisEnded = false
            warmupMs = leadingWarmupMsLocked()
        }
        Log.i(TAG, "speak start generation=${speechContext.generation}, textLength=${text.length}, warmup=${warmupMs}ms")

        try {
            track.play()
            enqueuePcm(speechContext, buildLeadingWarmup(warmupMs), warmup = true)

            val params = AiInput.builder()
                .param("vcn", "xiaoyan")    // 可选音色：xiaoyan / xiaofeng。
                .param("textEncoding", "UTF-8")
                .param("pitch", 50)
                .param("volume", 70)
                .param("speed", 50)
                .build()
            val newHandle = helper.start(ABILITY_ID, params, speechContext)
            if (newHandle.code != 0) error("启动语音合成失败：${newHandle.code}")

            synchronized(lock) {
                if (activeContext !== speechContext) {
                    runCatching { helper.end(newHandle) }
                    return@runCatching
                }
                handle = newHandle
                activeHandleId = newHandle.id
                activeHandleI = newHandle.i
            }
            Log.i(TAG, "aikit start ok generation=${speechContext.generation}, handleId=${newHandle.id}, handleI=${newHandle.i}")

            val input = AiText.get("text").data(text).valid()
            val code = helper.write(AiRequest.builder().payload(input).build(), newHandle, speechContext)
            if (code != 0) error("写入合成文本失败：$code")
        } catch (throwable: Throwable) {
            finish(speechContext, notify = false)
            throw throwable
        }
    }

    /** 停止当前播报且不触发完成回调，常用于切页或进入待唤醒。 */
    fun stop() {
        val oldHandle: AiHandle?
        val oldTrack: AudioTrack?
        synchronized(lock) {
            cancelPendingFinishLocked()
            generation += 1
            oldHandle = handle
            oldTrack = audioTrack
            handle = null
            activeContext = null
            activeHandleId = INVALID_HANDLE_ID
            activeHandleI = INVALID_HANDLE_ID
            audioTrack = null
            completion = null
            queuedAudioBytes = 0L
            audioChunkCount = 0
            synthesisEnded = false
        }
        oldHandle?.let { runCatching { helper.end(it) } }
        releaseTrackAsync(oldTrack)
    }

    /** 服务销毁时释放 TTS 引擎资源。 */
    fun release() {
        released = true
        stop()
        playbackExecutor.shutdownNow()
        helper.engineUnInit(ABILITY_ID)
    }

    /** SDK 结束或报错时统一收尾；notify 决定是否继续状态机。 */
    private fun finish(speechContext: SpeechContext, notify: Boolean) {
        val oldHandle: AiHandle?
        val oldTrack: AudioTrack?
        val callback: (() -> Unit)?
        synchronized(lock) {
            if (activeContext !== speechContext) return
            cancelPendingFinishLocked()
            oldHandle = handle
            oldTrack = audioTrack
            callback = completion
            handle = null
            activeContext = null
            activeHandleId = INVALID_HANDLE_ID
            activeHandleI = INVALID_HANDLE_ID
            audioTrack = null
            completion = null
            queuedAudioBytes = 0L
            audioChunkCount = 0
            synthesisEnded = false
            lastFinishedAtMs = SystemClock.elapsedRealtime()
        }
        oldHandle?.let { runCatching { helper.end(it) } }
        releaseTrackAsync(oldTrack)
        Log.i(TAG, "finish generation=${speechContext.generation}, notify=$notify")
        if (notify) callback?.invoke()
    }

    /** SDK 合成完成不等于 AudioTrack 已播完，短句要等队列实际 drain 后再释放。 */
    private fun scheduleFinishAfterPlaybackDrains(speechContext: SpeechContext) {
        val oldHandle: AiHandle?
        val delayMs: Long
        var finishImmediately = false
        synchronized(lock) {
            if (activeContext !== speechContext) return
            synthesisEnded = true
            cancelPendingFinishLocked()
            oldHandle = handle
            handle = null
            val track = audioTrack
            if (track == null) {
                finishImmediately = true
                delayMs = 0L
            } else {
                delayMs = remainingPlaybackMsLocked(track) + PLAYBACK_DRAIN_MARGIN_MS
            }
        }
        oldHandle?.let { runCatching { helper.end(it) } }
        if (finishImmediately) {
            finish(speechContext, notify = true)
            return
        }

        val runnable = Runnable { finish(speechContext, notify = true) }
        synchronized(lock) {
            if (activeContext !== speechContext) return
            finishRunnable = runnable
        }
        Log.i(TAG, "aikit end generation=${speechContext.generation}, delay=${delayMs}ms")
        main.postDelayed(runnable, delayMs)
    }

    /** 取消上一次等待 AudioTrack 播完的延迟释放任务。 */
    private fun cancelPendingFinishLocked() {
        finishRunnable?.let(main::removeCallbacks)
        finishRunnable = null
    }

    /** 释放 AudioTrack，忽略 stop 时可能因状态已停止产生的异常。 */
    private fun releaseTrackAsync(track: AudioTrack?) {
        if (track == null) return
        playbackExecutor.execute {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** 将合成或预热 PCM 送入播放线程，同时累计队列长度用于计算何时真正播完。 */
    private fun enqueuePcm(speechContext: SpeechContext, bytes: ByteArray, warmup: Boolean = false) {
        var shouldRescheduleFinish = false
        synchronized(lock) {
            if (activeContext !== speechContext) return
            queuedAudioBytes += bytes.size
            if (!warmup) audioChunkCount += 1
            shouldRescheduleFinish = synthesisEnded && finishRunnable != null
        }
        playbackExecutor.execute { writePcmOnPlaybackThread(speechContext, bytes) }
        if (warmup) {
            Log.i(TAG, "warmup queued generation=${speechContext.generation}, bytes=${bytes.size}")
        }
        if (shouldRescheduleFinish) scheduleFinishAfterPlaybackDrains(speechContext)
    }

    /** 播放前生成一段近似静音 PCM，防止冷启动时功放/音频路由吞掉“早上好”这类开头。 */
    private fun buildLeadingWarmup(durationMs: Int): ByteArray {
        val warmup = ByteArray(warmupByteCount(durationMs))
        var frame = 0
        var index = 0
        while (index + 1 < warmup.size) {
            val sample = if ((frame / WARMUP_HALF_WAVE_FRAMES) % 2 == 0) WARMUP_SAMPLE else -WARMUP_SAMPLE
            warmup[index] = (sample and 0xFF).toByte()
            warmup[index + 1] = ((sample shr 8) and 0xFF).toByte()
            frame += 1
            index += BYTES_PER_SAMPLE
        }
        return warmup
    }

    /** 创建 16k/16bit/mono 流式播放器，匹配 Aisound 返回的 PCM 参数。 */
    private fun newAudioTrack(): AudioTrack {
        val minSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = minSize.coerceAtLeast(warmupByteCount(COLD_START_WARMUP_MS))
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    /** 首次播报或长时间未播报时使用更长预热，后续短时间内播报使用较短预热。 */
    private fun leadingWarmupMsLocked(): Int {
        val elapsedSinceLastFinish = SystemClock.elapsedRealtime() - lastFinishedAtMs
        return if (lastFinishedAtMs == 0L || elapsedSinceLastFinish > COLD_START_INTERVAL_MS) {
            COLD_START_WARMUP_MS
        } else {
            WARM_START_WARMUP_MS
        }
    }

    private fun warmupByteCount(durationMs: Int): Int =
        SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * durationMs / 1000

    /** 播放线程中用非阻塞方式写 AudioTrack，避免长时间卡住 SDK 回调或 stop 流程。 */
    private fun writePcmOnPlaybackThread(speechContext: SpeechContext, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val track = synchronized(lock) {
                if (activeContext !== speechContext) return
                audioTrack
            } ?: return
            val written = runCatching {
                track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }.getOrElse { error ->
                reportPlaybackError(speechContext, "AudioTrack 写入异常：${error.message.orEmpty()}")
                return
            }
            when {
                written > 0 -> offset += written
                written == 0 -> SystemClock.sleep(NON_BLOCKING_WRITE_RETRY_DELAY_MS)
                else -> {
                    reportPlaybackError(speechContext, "AudioTrack 写入失败：$written")
                    return
                }
            }
        }
        if (offset > 0) maybeLogAudioProgress(speechContext)
    }

    /** 控制播放进度日志频率，避免像 AIKit 内部日志一样把 logcat 淹没。 */
    private fun maybeLogAudioProgress(speechContext: SpeechContext) {
        val shouldLog = synchronized(lock) {
            activeContext === speechContext && audioChunkCount > 0 && audioChunkCount % AUDIO_LOG_EVERY_CHUNKS == 0
        }
        if (shouldLog) {
            Log.d(TAG, "audio progress generation=${speechContext.generation}, chunks=$audioChunkCount, totalQueued=$queuedAudioBytes")
        }
    }

    /** 只处理当前播报的播放错误，过期 session 的错误直接忽略。 */
    private fun reportPlaybackError(speechContext: SpeechContext, message: String) {
        synchronized(lock) {
            if (activeContext !== speechContext) return
        }
        onError(message)
        finish(speechContext, notify = false)
    }

    /** 根据 SDK 回调 id/context 判断事件是否属于当前这次播报，防止旧回调误伤新播报。 */
    private fun currentContextOrNull(id: Int, callbackContext: Any?): SpeechContext? = synchronized(lock) {
        val current = activeContext ?: return@synchronized null
        val contextMatches = callbackContext === current
        val handleMatches = id != INVALID_HANDLE_ID && (id == activeHandleId || id == activeHandleI)
        if (contextMatches || handleMatches) current else null
    }

    private fun remainingPlaybackMsLocked(track: AudioTrack): Long {
        val queuedFrames = queuedAudioBytes / BYTES_PER_SAMPLE
        val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val remainingFrames = (queuedFrames - playedFrames).coerceAtLeast(0L)
        return remainingFrames * 1000L / SAMPLE_RATE_HZ
    }

    private class SpeechContext(val generation: Int)

    companion object {
        private const val TAG = "RobotTTS"
        private const val ABILITY_ID = "ece9d3c90"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val WARM_START_WARMUP_MS = 250
        private const val COLD_START_WARMUP_MS = 900
        private const val COLD_START_INTERVAL_MS = 10_000L
        private const val PLAYBACK_DRAIN_MARGIN_MS = 220L
        private const val WARMUP_SAMPLE = 12
        private const val WARMUP_HALF_WAVE_FRAMES = 18
        private const val NON_BLOCKING_WRITE_RETRY_DELAY_MS = 8L
        private const val AUDIO_LOG_EVERY_CHUNKS = 12
        private const val INVALID_HANDLE_ID = -1
    }
}
