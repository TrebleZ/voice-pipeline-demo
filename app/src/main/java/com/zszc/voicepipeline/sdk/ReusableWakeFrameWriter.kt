package com.zszc.voicepipeline.sdk

import com.iflytek.aikit.core.AiStatus

/**
 * 复用一份 AIKit 请求构建器来写入连续音频帧。
 *
 * AIKit 的 Builder 持有原生参数句柄；每 40ms 新建一份只能依赖 GC/finalize 回收，
 * 会让常驻唤醒场景中的原生构建器持续堆积。每次写入前后清空同一 Builder，既避免
 * 上一帧数据残留，也把原生句柄数量保持为常量。
 */
internal class ReusableWakeFrameWriter<Builder, Payload, Request>(
    createBuilder: () -> Builder,
    createPayload: () -> Payload,
    private val clearBuilder: (Builder) -> Unit,
    private val updatePayload: (Payload, ByteArray, AiStatus) -> Unit,
    private val buildRequest: (Builder, Payload) -> Request,
    private val writeRequest: (Request) -> Int,
) {
    private val builder = createBuilder()
    private val payload = createPayload()

    /** 构建并同步写入一帧；写入返回后立即清掉本帧关联的原生参数。 */
    @Synchronized
    fun write(bytes: ByteArray, status: AiStatus): Int {
        clearBuilder(builder)
        return try {
            updatePayload(payload, bytes, status)
            writeRequest(buildRequest(builder, payload))
        } finally {
            clearBuilder(builder)
        }
    }
}
