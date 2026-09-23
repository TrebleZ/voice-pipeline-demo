package com.zszc.voicepipeline.sdk

import android.media.MediaRecorder
import android.util.Log
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIKit 离线唤醒封装。
 *
 * Demo 处于 standby 时启动本模块持续读取麦克风 PCM，命中“小飞小飞”后停止唤醒，
 * 并把控制权交给页面的连续对话流程。
 */
class WakeWordEngine(
    private val workDir: File,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val helper = AiHelper.getInst()
    private val recorder = PcmRecorder()
    @Volatile private var handle: AiHandle? = null
    private val frameWriter = ReusableWakeFrameWriter(
        createBuilder = { AiRequest.builder() },
        createPayload = { AiAudio.get("wav") },
        clearBuilder = { it.clear() },
        updatePayload = { payload, bytes, status ->
            payload.data(bytes).status(status)
        },
        buildRequest = { builder, payload ->
            builder.payload(payload.valid()).build()
        },
        writeRequest = { request -> handle?.let { helper.write(request, it) } ?: 0 },
    )
    private val audioStream = WakeAudioStream(frameWriter::write)
    private val running = AtomicBoolean(false)
    private val keywordSessionData = WakeKeywordSessionData(
        load = {
            prepareKeyword()
            val request = AiRequest.builder().customText("key_word", keywordFile.absolutePath, 0).build()
            checkCode(helper.loadData(ABILITY_ID, request), "加载唤醒词")
            Log.i(TAG, "keyword loaded: ${keywordFile.absolutePath}")
        },
        bind = {
            checkCode(helper.specifyDataSet(ABILITY_ID, "key_word", intArrayOf(0)), "启用唤醒词")
            Log.i(TAG, "keyword dataset bound")
        },
        unload = { helper.unLoadData(ABILITY_ID, "key_word", 0) },
    )

    init {
        helper.registerListener(ABILITY_ID, object : AiListener {
            override fun onResult(id: Int, output: MutableList<AiResponse>?, context: Any?) {
                val keys = output.orEmpty().map { it.key }
                if (keys.isNotEmpty()) Log.i(TAG, "onResult handle=$id keys=$keys")
                // 讯飞 demo 同时处理 func_wake_up / func_pre_wakeup；compareAndSet 防止重复触发。
                if (keys.any { it == "func_wake_up" || it == "func_pre_wakeup" } && running.compareAndSet(true, false)) {
                    Log.i(TAG, "wake word matched: $WAKE_WORD")
                    onWake()
                }
            }

            override fun onEvent(id: Int, event: Int, data: MutableList<AiResponse>?, context: Any?) {
                Log.d(TAG, "onEvent handle=$id event=$event")
            }

            override fun onError(id: Int, error: Int, message: String?, context: Any?) {
                Log.e(TAG, "onError handle=$id error=$error message=${message.orEmpty()}")
                if (running.compareAndSet(true, false)) onError("唤醒引擎错误 $error：${message.orEmpty()}")
            }
        })
    }

    /** 启动唤醒监听：准备关键词、启动 AIKit handle，并开始持续写入麦克风 PCM。 */
    fun start(): Result<Unit> = runCatching {
        if (!running.compareAndSet(false, true)) return Result.success(Unit)
        keywordSessionData.prepare()
        val params = AiRequest.builder()
            .param("wdec_param_nCmThreshold", "0 0:800")
            .param("gramLoad", true)
            .build()
        val newHandle = helper.start(ABILITY_ID, params, null)
        if (newHandle.code != 0) error("启动唤醒引擎失败：${newHandle.code}")
        handle = newHandle
        audioStream.reset()
        Log.i(TAG, "start ok handleId=${newHandle.id}, handleI=${newHandle.i}, keyword=$WAKE_WORD")
        recorder.start(MediaRecorder.AudioSource.MIC, ::writePcm) { error ->
            Log.e(TAG, "recorder error", error)
            onError(error.message ?: "麦克风读取失败")
        }
    }.onFailure { running.set(false) }

    /** 停止本轮唤醒监听，但保留已加载的唤醒词数据，便于回待命时快速启动。 */
    fun stop() {
        running.set(false)
        runCatching { audioStream.close() }
            .onFailure { Log.w(TAG, "send wake end frame failed", it) }
        recorder.stop()
        handle?.let { runCatching { helper.end(it) } }
        handle = null
        Log.i(TAG, "stopped")
    }

    /** 服务销毁时释放 AIKit 唤醒能力和已加载的关键词数据。 */
    fun release() {
        stop()
        keywordSessionData.release()
        helper.engineUnInit(ABILITY_ID)
    }

    /** 将麦克风 PCM 写入 AIKit 唤醒引擎。 */
    private fun writePcm(bytes: ByteArray) {
        if (!running.get()) return
        val code = audioStream.write(bytes)
        if (code != 0 && running.compareAndSet(true, false)) onError("唤醒音频写入失败：$code")
    }

    /** 运行时生成实际使用的关键词文件，避免依赖 Demo 中旧的 keyword1.txt。 */
    private val keywordFile get() = File(workDir, "ivw/keyword.txt")

    /** 写入当前 Demo 指定的“小飞小飞”，并删除旧二进制缓存。 */
    private fun prepareKeyword() {
        keywordFile.parentFile?.mkdirs()
        keywordFile.writeText("$WAKE_WORD;\n", Charsets.UTF_8)
        File(workDir, "ivw/keyword.bin").delete()
    }

    /** AIKit 大多数 API 用 Int 返回码，统一在这里转成异常文案。 */
    private fun checkCode(code: Int, action: String) {
        if (code != 0) error("$action 失败：$code")
    }

    companion object {
        private const val TAG = "RobotWake"
        const val WAKE_WORD = "小飞小飞"
        private const val ABILITY_ID = "e867a88f2"
    }
}
