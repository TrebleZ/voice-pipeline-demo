package com.zszc.voicepipeline.sdk

import android.content.Context
import java.io.File

/**
 * 离线资源安装器。
 *
 * 将 APK assets/iflytek 下的模型释放到应用私有目录，替代 Demo 里手工放 /sdcard 的方式。
 */
class ResourceInstaller(private val context: Context) {
    /** AIKit 的 workDir，唤醒/TTS 都从这里读取资源和写日志。 */
    val workDir: File = File(context.filesDir, "iflytek")
    /** 资源版本标记；资源目录结构变化时改名即可触发重新释放。 */
    private val marker = File(workDir, ".resource-v2")

    /** 首次启动或资源版本变化时复制模型文件，其余启动直接复用已有目录。 */
    fun installIfNeeded(onProgress: (String) -> Unit = {}) {
        if (marker.exists()) return
        workDir.mkdirs()
        // ASR 已改用 SparkChain 在线实时转写，升级时清理不再使用的离线命令词模型。
        File(workDir, "CNENESR").deleteRecursively()
        File(workDir, ".resource-v1").delete()
        listOf("ivw", "aisound").forEach { folder ->
            onProgress("正在部署 $folder 离线资源")
            copyAssetTree("iflytek/$folder", File(workDir, folder))
        }
        marker.writeText("1")
    }

    /** 递归复制 assets 目录；AssetManager 无法直接区分文件/目录，只能通过 children 判断。 */
    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().buffered().use(input::copyTo)
            }
            return
        }
        destination.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
    }
}
