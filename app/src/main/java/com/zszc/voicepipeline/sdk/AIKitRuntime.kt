package com.zszc.voicepipeline.sdk

import android.content.Context
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import com.zszc.voicepipeline.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIKit 离线能力运行时。
 *
 * 目前负责离线唤醒和 Aisound 离线 TTS 的统一初始化/鉴权。
 * 真实密钥从 local.properties 注入 BuildConfig，避免写进仓库。
 */
class AIKitRuntime(
    private val context: Context,
    private val workDir: File,
) {
    /** 鉴权成功后置 true，后续 startService 不重复初始化。 */
    private val initialized = AtomicBoolean(false)
    /** 记录是否调用过 initEntry，release 时才需要 unInit。 */
    private val initStarted = AtomicBoolean(false)
    /** AIKit 鉴权回调可能多次到达，只向上层交付一次结果。 */
    private val callbackDelivered = AtomicBoolean(false)

    /** 初始化 AIKit，并通过 CoreListener 返回鉴权成功或失败。 */
    fun initialize(callback: (Result<Unit>) -> Unit) {
        if (initialized.get()) {
            callback(Result.success(Unit))
            return
        }
        if (BuildConfig.IFLYTEK_APP_ID.isBlank() ||
            BuildConfig.IFLYTEK_API_KEY.isBlank() ||
            BuildConfig.IFLYTEK_API_SECRET.isBlank()
        ) {
            callback(Result.failure(IllegalStateException("请先在 local.properties 配置讯飞 appId、apiKey、apiSecret")))
            return
        }
        val helper = AiHelper.getInst()
        helper.registerListener(CoreListener { type, code ->
            if (type == ErrType.AUTH) {
                // code=0 表示鉴权通过；其它码直接展示给 UI，便于排查讯飞后台授权问题。
                if (code == 0) {
                    initialized.set(true)
                    if (callbackDelivered.compareAndSet(false, true)) callback(Result.success(Unit))
                } else if (callbackDelivered.compareAndSet(false, true)) {
                    callback(Result.failure(IllegalStateException("讯飞 SDK 鉴权失败：$code")))
                }
            }
        })
        File(workDir, "logs").mkdirs()
        // SDK 日志落到应用私有目录，便于 adb run-as 或导出后定位鉴权/能力码问题。
        helper.setLogInfo(LogLvl.INFO, 1, File(workDir, "logs/aikit.log").absolutePath)
        val params = BaseLibrary.Params.builder()
            .appId(BuildConfig.IFLYTEK_APP_ID)
            .apiKey(BuildConfig.IFLYTEK_API_KEY)
            .apiSecret(BuildConfig.IFLYTEK_API_SECRET)
            .workDir(workDir.absolutePath + File.separator)
            .build()
        initStarted.set(true)
        Thread({ helper.initEntry(context.applicationContext, params) }, "aikit-init").start()
    }

    /** 释放 AIKit 全局运行时；由前台服务 onDestroy 统一调用。 */
    fun release() {
        callbackDelivered.set(true)
        initialized.set(false)
        if (initStarted.compareAndSet(true, false)) AiHelper.getInst().unInit()
    }
}
