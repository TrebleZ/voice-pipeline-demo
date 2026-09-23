package com.zszc.voicepipeline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zszc.voicepipeline.llm.SparkChatMessage
import com.zszc.voicepipeline.llm.SparkHttpClient
import com.zszc.voicepipeline.sdk.AIKitRuntime
import com.zszc.voicepipeline.sdk.ResourceInstaller
import com.zszc.voicepipeline.sdk.SpeechRecognizer
import com.zszc.voicepipeline.sdk.SpeechSynthesizer
import com.zszc.voicepipeline.sdk.SparkChainRuntime
import com.zszc.voicepipeline.sdk.WakeWordEngine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 语音链路独立 Demo。
 *
 * 页面只演示“唤醒 -> 实时转写 -> 星火回复 -> TTS 播报”，不承载 Launcher 的悬浮窗、
 * 天气、应用启动或本地指令路由。
 */
class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val stateMachine = VoicePipelineDemoStateMachine()
    private val bootstrapping = AtomicBoolean(false)
    private val sdkReady = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val sessionGeneration = AtomicInteger(0)
    private val history = ArrayDeque<SparkChatMessage>()

    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var replyText: TextView
    private lateinit var statusBadge: TextView
    private lateinit var voiceWave: VoiceWaveView
    private lateinit var startButton: Button
    private lateinit var sleepButton: Button

    private var runtime: AIKitRuntime? = null
    private var sparkRuntime: SparkChainRuntime? = null
    private var wakeEngine: WakeWordEngine? = null
    private var recognizer: SpeechRecognizer? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var conversationActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || hasAudioPermission()) startDemo() else publish(stateMachine.error("缺少麦克风权限"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enterImmersiveMode()
        bindViews()
        bindActions()
        publish(stateMachine.current())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun bindViews() {
        titleText = findViewById(R.id.voiceDemoTitle)
        detailText = findViewById(R.id.voiceDemoDetail)
        transcriptText = findViewById(R.id.voiceDemoTranscript)
        replyText = findViewById(R.id.voiceDemoReply)
        statusBadge = findViewById(R.id.voiceDemoStatusBadge)
        voiceWave = findViewById(R.id.voiceDemoWave)
        startButton = findViewById(R.id.voiceDemoStartButton)
        sleepButton = findViewById(R.id.voiceDemoSleepButton)
    }

    private fun bindActions() {
        startButton.setOnClickListener {
            if (hasAudioPermission()) startDemo() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        sleepButton.setOnClickListener { enterStandby() }
        findViewById<Button>(R.id.voiceDemoCloseButton).setOnClickListener { finish() }
    }

    private fun startDemo() {
        if (sdkReady.get()) {
            enterStandby()
            return
        }
        val generation = sessionGeneration.incrementAndGet()
        if (!bootstrapping.compareAndSet(false, true)) return
        publish(stateMachine.installing("复制 APK 内置唤醒和 TTS 模型"))
        executeWorker(generation) {
            val installer = ResourceInstaller(this)
            runCatching {
                installer.installIfNeeded { progress -> publish(stateMachine.installing(progress)) }
            }.onFailure {
                bootstrapping.set(false)
                publish(stateMachine.error("资源部署失败：${it.message.orEmpty()}"))
                return@executeWorker
            }

            publish(stateMachine.initializing("AIKit 鉴权中"))
            val newRuntime = AIKitRuntime(applicationContext, installer.workDir)
            runtime = newRuntime
            newRuntime.initialize { result ->
                if (!isActiveSession(generation)) {
                    newRuntime.release()
                    return@initialize
                }
                result.onSuccess {
                    executeWorker(generation) {
                        publish(stateMachine.initializing("SparkChain 实时转写初始化中"))
                        val newSparkRuntime = SparkChainRuntime(applicationContext)
                        val sparkResult = newSparkRuntime.initialize()
                        main.post {
                            if (!isActiveSession(generation)) {
                                newSparkRuntime.release()
                                return@post
                            }
                            sparkResult.onSuccess {
                                sparkRuntime = newSparkRuntime
                                createEngines(installer)
                                sdkReady.set(true)
                                bootstrapping.set(false)
                                enterStandby()
                            }.onFailure {
                                newSparkRuntime.release()
                                newRuntime.release()
                                runtime = null
                                bootstrapping.set(false)
                                publish(stateMachine.error("SparkChain 初始化失败：${it.message.orEmpty()}"))
                            }
                        }
                    }
                }.onFailure {
                    main.post {
                        newRuntime.release()
                        runtime = null
                        bootstrapping.set(false)
                        publish(stateMachine.error("AIKit 初始化失败：${it.message.orEmpty()}"))
                    }
                }
            }
        }
    }

    private fun createEngines(installer: ResourceInstaller) {
        wakeEngine = WakeWordEngine(
            installer.workDir,
            onWake = { main.post(::handleWake) },
            onError = ::handleEngineError,
        )
        recognizer = SpeechRecognizer(
            onPartial = { text -> main.post { handlePartial(text) } },
            onFinal = { text -> main.post { handleRecognized(text) } },
            onError = ::handleEngineError,
        )
        synthesizer = SpeechSynthesizer(::handleEngineError)
    }

    private fun enterStandby() {
        if (!sdkReady.get()) return
        conversationActive = false
        recognizer?.stop()
        synthesizer?.stop()
        publish(stateMachine.standby())
        wakeEngine?.start()?.onFailure { handleEngineError(it.message.orEmpty()) }
    }

    private fun handleWake() {
        if (stateMachine.current().phase != VoicePipelineDemoPhase.STANDBY) return
        wakeEngine?.stop()
        conversationActive = true
        publish(stateMachine.awakened(WakeWordEngine.WAKE_WORD))
        speak("我在，请讲") { startListening() }
    }

    private fun startListening() {
        if (!conversationActive) return
        publish(stateMachine.listening())
        recognizer?.start()?.onFailure { handleEngineError(it.message.orEmpty()) }
    }

    private fun handlePartial(text: String) {
        if (!conversationActive || text.isBlank()) return
        publish(stateMachine.listening(text))
    }

    private fun handleRecognized(rawText: String) {
        if (!conversationActive) return
        val text = rawText.trim()
        if (text.isBlank()) {
            startListening()
            return
        }
        if (isDemoExit(text)) {
            conversationActive = false
            speak("好的，语音 Demo 已回到待唤醒") { enterStandby() }
            return
        }
        askSpark(text)
    }

    private fun askSpark(text: String) {
        publish(stateMachine.thinking(text))
        val generation = sessionGeneration.get()
        executeWorker(generation) {
            val client = SparkHttpClient()
            val reply = if (client.isConfigured()) {
                val snapshot = synchronized(history) { history.toList() }
                client.chat(text, snapshot)
                    .onSuccess { rememberTurn(text, it) }
                    .getOrElse { "星火大模型暂时没有返回成功结果，请稍后再试" }
            } else {
                "还没有配置星火大模型 APIPassword，请先在 local.properties 中配置 iflytek.sparkApiPassword"
            }
            main.post {
                if (conversationActive && isActiveSession(generation)) speak(reply) { startListening() }
            }
        }
    }

    private fun rememberTurn(userText: String, assistantText: String) {
        synchronized(history) {
            history.addLast(SparkChatMessage("user", userText))
            history.addLast(SparkChatMessage("assistant", assistantText))
            while (history.size > MAX_HISTORY_MESSAGES) history.removeFirst()
        }
    }

    private fun speak(text: String, onComplete: () -> Unit) {
        publish(stateMachine.speaking(text))
        synthesizer?.speak(text) { main.post(onComplete) }
            ?.onFailure { handleEngineError(it.message.orEmpty()) }
    }

    private fun handleEngineError(message: String) {
        main.post {
            conversationActive = false
            wakeEngine?.stop()
            recognizer?.stop()
            synthesizer?.stop()
            publish(stateMachine.error(message))
        }
    }

    private fun publish(state: VoicePipelineDemoUiState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publish(state) }
            return
        }
        titleText.text = state.title
        detailText.text = state.detail
        transcriptText.text = state.transcript.ifBlank { getString(R.string.voice_demo_empty_transcript) }
        replyText.text = state.modelReply.ifBlank { getString(R.string.voice_demo_empty_reply) }
        statusBadge.text = state.phase.name
        voiceWave.setWaveActive(state.phase == VoicePipelineDemoPhase.AWAKENED ||
            state.phase == VoicePipelineDemoPhase.LISTENING ||
            state.phase == VoicePipelineDemoPhase.THINKING ||
            state.phase == VoicePipelineDemoPhase.SPEAKING)
        startButton.isEnabled = state.phase != VoicePipelineDemoPhase.INSTALLING &&
            state.phase != VoicePipelineDemoPhase.INITIALIZING
        sleepButton.isEnabled = sdkReady.get() && state.phase != VoicePipelineDemoPhase.STANDBY
    }

    override fun onDestroy() {
        destroyed.set(true)
        releaseDemoResources(updateUi = false)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        releaseDemoResources(updateUi = !isFinishing)
    }

    private fun releaseDemoResources(updateUi: Boolean) {
        sessionGeneration.incrementAndGet()
        conversationActive = false
        sdkReady.set(false)
        bootstrapping.set(false)
        wakeEngine?.release()
        wakeEngine = null
        recognizer?.release()
        recognizer = null
        sparkRuntime?.release()
        sparkRuntime = null
        synthesizer?.release()
        synthesizer = null
        runtime?.release()
        runtime = null
        synchronized(history) { history.clear() }
        if (updateUi) publish(stateMachine.stopped())
    }

    private fun executeWorker(generation: Int, block: () -> Unit) {
        if (!isActiveSession(generation) || worker.isShutdown) return
        runCatching {
            worker.execute {
                if (isActiveSession(generation)) block()
            }
        }
    }

    private fun isActiveSession(generation: Int): Boolean =
        !destroyed.get() && sessionGeneration.get() == generation

    private fun enterImmersiveMode() {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val MAX_HISTORY_MESSAGES = 8

        fun createIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
    }
}

private fun isDemoExit(rawText: String): Boolean {
    val normalized = rawText.replace(Regex("[\\s，。！？,.!?]"), "")
    return normalized in setOf("退出对话", "结束对话", "不用了", "退出演示", "结束演示")
}
