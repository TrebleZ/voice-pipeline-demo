package com.zszc.voicepipeline

/**
 * 独立语音链路 Demo 的 UI 阶段。
 *
 * 这组状态只服务新 Demo 页面，不复用 Launcher 的 AssistantPhase，避免示例页被桌面业务绑住。
 */
enum class VoicePipelineDemoPhase {
    STOPPED,
    INSTALLING,
    INITIALIZING,
    STANDBY,
    AWAKENED,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR,
}

data class VoicePipelineDemoUiState(
    val phase: VoicePipelineDemoPhase = VoicePipelineDemoPhase.STOPPED,
    val title: String = "语音 Demo 未启动",
    val detail: String = "点击启动后，说“小飞小飞”唤醒",
    val transcript: String = "",
    val modelReply: String = "",
)

class VoicePipelineDemoStateMachine {
    private var state = VoicePipelineDemoUiState()

    fun current(): VoicePipelineDemoUiState = state

    fun installing(detail: String): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.INSTALLING,
        title = "准备语音资源",
        detail = detail,
        resetTurn = true,
    )

    fun initializing(detail: String): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.INITIALIZING,
        title = "初始化讯飞能力",
        detail = detail,
        resetTurn = true,
    )

    fun standby(): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.STANDBY,
        title = "等待唤醒",
        detail = "请说“小飞小飞”",
        resetTurn = true,
    )

    fun awakened(wakeWord: String): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.AWAKENED,
        title = "已唤醒",
        detail = wakeWord,
    )

    fun listening(transcript: String = ""): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.LISTENING,
        title = "实时转写中",
        detail = if (transcript.isBlank()) "请讲话" else "SparkChain ASR 返回中间结果",
        transcript = transcript,
    )

    fun thinking(userText: String): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.THINKING,
        title = "等待星火回复",
        detail = "正在请求星火 HTTP 大模型",
        transcript = userText,
        modelReply = "",
    )

    fun speaking(reply: String): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.SPEAKING,
        title = "TTS 播报中",
        detail = "AIKit Aisound 正在播放星火回复",
        modelReply = reply,
    )

    fun error(message: String): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.ERROR,
        title = "语音链路异常",
        detail = message,
    )

    fun stopped(): VoicePipelineDemoUiState = update(
        phase = VoicePipelineDemoPhase.STOPPED,
        title = "语音 Demo 已停止",
        detail = "点击启动重新初始化",
        resetTurn = true,
    )

    private fun update(
        phase: VoicePipelineDemoPhase,
        title: String,
        detail: String,
        transcript: String = state.transcript,
        modelReply: String = state.modelReply,
        resetTurn: Boolean = false,
    ): VoicePipelineDemoUiState {
        state = VoicePipelineDemoUiState(
            phase = phase,
            title = title,
            detail = detail,
            transcript = if (resetTurn) "" else transcript,
            modelReply = if (resetTurn) "" else modelReply,
        )
        return state
    }
}
