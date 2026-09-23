package com.zszc.voicepipeline.sdk

import com.iflytek.aikit.core.AiStatus

/**
 * 唤醒音频流帧状态管理。
 *
 * AIKit IVW 唤醒能力要求一轮音频流具备明确的 BEGIN/CONTINUE/END 状态。
 * 如果命中唤醒词后直接 end handle，而没有补 END 尾帧，部分设备上下一轮
 * 唤醒会话虽然能 start 成功，但不再产生命中回调。
 */
internal class WakeAudioStream(
    private val writer: (ByteArray, AiStatus) -> Int,
) {
    private var started = false
    private var closed = false

    /** 新的一轮 AIKit handle 启动后重置音频流状态。 */
    @Synchronized
    fun reset() {
        started = false
        closed = false
    }

    /** 写入普通 PCM 帧：第一帧为 BEGIN，后续为 CONTINUE。 */
    @Synchronized
    fun write(bytes: ByteArray): Int {
        if (closed) return 0
        val status = if (started) AiStatus.CONTINUE else AiStatus.BEGIN
        started = true
        return writer(bytes, status)
    }

    /** 结束已开始的音频流，确保 AIKit 在 end(handle) 前收到 END 尾帧。 */
    @Synchronized
    fun close(): Int {
        if (closed) return 0
        closed = true
        return if (started) writer(ByteArray(0), AiStatus.END) else 0
    }
}
