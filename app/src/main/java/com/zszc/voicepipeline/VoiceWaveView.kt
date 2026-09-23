package com.zszc.voicepipeline

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 语音状态卡片左侧的小波形。
 *
 * 非唤醒状态显示静态五段柱；唤醒/播报/聆听/执行时启动轻量动画，
 * 用于替代全卡片光晕，贴近原型图里“文字前面的波形”。
 */
class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 166, 26)
        style = Paint.Style.FILL
    }
    private val idleHeights = floatArrayOf(0.34f, 0.58f, 0.82f, 0.58f, 0.34f)
    private var progress = 0f
    private var active = false

    /** 只维护一个无限循环动画；实际绘制高度在 onDraw 中按 progress 计算。 */
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 外部语音状态切换时调用，避免重复 start/cancel 带来的闪烁。 */
    fun setWaveActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (active && isAttachedToWindow) {
            animator.start()
        } else {
            animator.cancel()
            progress = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = dp(2.4f)
        val gap = dp(3f)
        val totalWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * gap
        val startX = (width - totalWidth) / 2f
        val centerY = height / 2f
        val maxHeight = height * 0.72f

        repeat(BAR_COUNT) { index ->
            val heightScale = if (active) {
                // 每根柱子的相位稍微错开，产生自然的声波起伏。
                0.32f + abs(sin(progress * PI * 2 + index * 0.7)).toFloat() * 0.68f
            } else {
                idleHeights[index]
            }
            val barHeight = maxHeight * heightScale
            val left = startX + index * (barWidth + gap)
            canvas.drawRoundRect(
                left,
                centerY - barHeight / 2f,
                left + barWidth,
                centerY + barHeight / 2f,
                barWidth / 2f,
                barWidth / 2f,
                paint,
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val BAR_COUNT = 5
    }
}
