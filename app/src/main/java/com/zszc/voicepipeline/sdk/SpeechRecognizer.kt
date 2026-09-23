package com.zszc.voicepipeline.sdk

import android.media.MediaRecorder
import android.util.Log
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import com.iflytek.sparkchain.core.asr.AudioAttributes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 来自 SparkChain bmcDemoActivity 的中文大模型识别能力。
 * 麦克风 PCM 实时送入在线 ASR，wpgs 中间结果用于同步刷新 Launcher 和悬浮窗。
 */
class SpeechRecognizer(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val asr = ASR("zh_cn", "slm", "mandarin")
    private val recorder = PcmRecorder()
    /** 当前是否正在把麦克风数据送给 SparkChain ASR。 */
    private val running = AtomicBoolean(false)

    /** 最新 wpgs 合并文本；最终回调也复用这份内容。 */
    @Volatile
    private var latestText = ""

    init {
        asr.registerCallbacks(object : AsrCallbacks {
            override fun onResult(result: ASR.ASRResult, context: Any?) {
                val text = result.bestMatchText?.trim().orEmpty()
                if (text.isNotBlank() && text != latestText) {
                    latestText = text
                    onPartial(text)
                }
                if (result.status == RESULT_STATUS_END && running.compareAndSet(true, false)) {
                    recorder.stop()
                    onFinal(latestText)
                }
            }

            override fun onError(error: ASR.ASRError, context: Any?) {
                finishWithError("实时转写错误 ${error.code}：${error.errMsg.orEmpty()}")
            }

            override fun onEndOfSpeech() {
                // 云端 VAD 已判定讲话结束后立即停止送音频，避免最终结果返回前多写一帧。
                if (running.get()) recorder.stop()
            }
        })
    }

    /** 开启一轮在线实时转写，输入为 16k/16bit/mono raw PCM。 */
    fun start(audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): Result<Unit> = runCatching {
        if (!running.compareAndSet(false, true)) return Result.success(Unit)
        latestText = ""
        asr.dwa("wpgs")
        val attributes = AudioAttributes().apply {
            sampleRate = 16000
            encoding = "raw"
            channels = 1
            bitdepth = 16
        }
        val code = asr.start(attributes, null)
        check(code == 0) { "启动实时转写失败：$code" }
        recorder.start(audioSource = audioSource, onPcm = ::writePcm) { error ->
            finishWithError(error.message ?: "麦克风读取失败")
        }
    }.onFailure {
        if (running.compareAndSet(true, false)) runCatching { asr.stop(true) }
    }

    /** 主动停止转写，并通知 SDK 结束本轮会话。 */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            recorder.stop()
            runCatching { asr.stop(true) }
        } else {
            recorder.stop()
        }
    }

    fun release() = stop()

    /** 将本地采集的 PCM 写入 SparkChain；会话已关闭的竞态按正常收尾处理。 */
    private fun writePcm(bytes: ByteArray) {
        if (!running.get()) return
        val code = asr.write(bytes)
        when {
            code == 0 -> Unit
            isSessionClosedWriteCode(code) -> {
                // SDK 的云端 VAD 可能先释放 handle，再投递 status=2 最终结果。
                // 此时停止送音频并等待最终回调，不能把正常收尾升级为语音能力异常。
                Log.w(TAG, "实时转写会话已结束，停止送音频：$code")
                recorder.stop()
            }
            else -> finishWithError("实时转写音频写入失败：$code")
        }
    }

    private fun finishWithError(message: String) {
        if (!running.compareAndSet(true, false)) return
        Log.e(TAG, message)
        recorder.stop()
        runCatching { asr.stop(true) }
        onError(message)
    }

    private companion object {
        const val TAG = "SparkChainASR"
        const val RESULT_STATUS_END = 2
    }
}

/** SparkChain 在最终回调前关闭写入端时可能返回这些码，属于正常停止竞态。 */
internal fun isSessionClosedWriteCode(code: Int): Boolean = code in 18305..18307
