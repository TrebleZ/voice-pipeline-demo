package com.zszc.voicepipeline.sdk

import android.content.Context
import com.iflytek.sparkchain.core.AiHelper
import com.iflytek.sparkchain.core.LogLvl
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.zszc.voicepipeline.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SparkChain 在线运行时。
 *
 * 当前只启用中文大模型实时转写，不承载 Demo 中的 LLM、文件识别等其它能力。
 */
class SparkChainRuntime(private val context: Context) {
    /** SparkChain 是全局单例，防止重复 init/unInit。 */
    private val initialized = AtomicBoolean(false)

    /** 使用 local.properties 注入的讯飞凭据初始化 SparkChain。 */
    fun initialize(): Result<Unit> = runCatching {
        check(BuildConfig.IFLYTEK_APP_ID.isNotBlank() &&
            BuildConfig.IFLYTEK_API_KEY.isNotBlank() &&
            BuildConfig.IFLYTEK_API_SECRET.isNotBlank()
        ) { "请先在 local.properties 配置讯飞 appId、apiKey、apiSecret" }

        val workDir = File(context.filesDir, "sparkchain").apply(File::mkdirs)
        // 部分设备网络检测不稳定，鉴权仍由 SDK API 鉴权流程处理。
        AiHelper.getInst().setConfig("skipNetworkCheck", true)
        AiHelper.getInst().setConfig("auth", "API")
        val config = SparkChainConfig.builder()
            .appID(BuildConfig.IFLYTEK_APP_ID)
            .apiKey(BuildConfig.IFLYTEK_API_KEY)
            .apiSecret(BuildConfig.IFLYTEK_API_SECRET)
            .workDir(workDir.absolutePath)
            .logLevel(LogLvl.INFO.value)
        val code = SparkChain.getInst().init(context.applicationContext, config)
        check(code == 0) { "SparkChain SDK 初始化失败：$code" }
        initialized.set(true)
    }

    /** 服务退出时关闭 SparkChain 全局实例。 */
    fun release() {
        if (initialized.compareAndSet(true, false)) SparkChain.getInst().unInit()
    }
}
