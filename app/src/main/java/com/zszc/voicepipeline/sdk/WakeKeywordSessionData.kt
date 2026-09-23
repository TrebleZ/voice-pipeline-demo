package com.zszc.voicepipeline.sdk

/**
 * 管理离线唤醒关键词数据的生命周期。
 *
 * 关键词文件只需加载一次，但 AIKit 的数据集绑定属于当前唤醒会话；每次重新
 * start 前都要再次绑定，否则部分设备会出现会话启动成功却不再返回唤醒结果。
 */
internal class WakeKeywordSessionData(
    private val load: () -> Unit,
    private val bind: () -> Unit,
    private val unload: () -> Unit,
) {
    private var loaded = false

    /** 确保关键词已加载，并为本次新会话重新绑定数据集。 */
    @Synchronized
    fun prepare() {
        if (!loaded) {
            load()
            loaded = true
        }
        bind()
    }

    /** 服务彻底退出时卸载已加载的关键词数据。 */
    @Synchronized
    fun release() {
        if (!loaded) return
        unload()
        loaded = false
    }
}
