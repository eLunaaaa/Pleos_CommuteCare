/**
 * @file VoiceMicPulseView.kt
 * @brief 유튜브 뮤직 음성 검색 스타일의 실시간 음량 반응형 마이크 펄스 & 사운드바 애니메이션 뷰.
 *
 * 동작 순서:
 * 1. 중앙에 플레오스 블루(#2563EB) 원형 마이크 배지를 배치한다.
 * 2. 음성 청취(isListening) 중 소리 크기(amplitude)와 발화 리듬에 맞춰 외곽 동심원 링 2개가 실시간으로 커졌다 작아진다.
 * 3. 마이크 좌우에 2D 캡슐형 사운드 웨이브 바를 배치하여 음성 입력 상태를 직관적으로 시각화한다.
 */
package ai.pleos.commutecare.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceMicPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var phase = 0f
    private var lastDrawNanos = 0L
    private var isActive = false
    private var targetLevel = 0.65f
    private var currentLevel = 0.4f

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 59, 130, 246)
    }

    private val midRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(85, 37, 99, 235)
    }

    private val coreCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2563EB")
    }

    private val micStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val micFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3B82F6")
    }

    private val rect = RectF()

    /**
     * @brief 마이크 팝업 애니메이션을 시작한다.
     * @return Unit
     */
    fun startPulse() {
        isActive = true
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * @brief 마이크 팝업 애니메이션을 중지한다.
     * @return Unit
     */
    fun stopPulse() {
        isActive = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /**
     * @brief 외부 RMS/음량 레벨(0.0~1.0)을 반영한다.
     * @param level 정규화된 음량 크기
     * @return Unit
     */
    fun updateVoiceLevel(level: Float) {
        targetLevel = level.coerceIn(0.15f, 1.0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isActive || visibility != VISIBLE) return
        if (frameTimeNanos - lastDrawNanos >= 32_000_000L) {
            lastDrawNanos = frameTimeNanos
            phase = (phase + 0.24f) % (2f * PI.toFloat())
            // 실제 사람 말소리의 억양처럼 다중 주파수 엔벨로프 생성
            val envelope = 0.45f + 0.35f * abs(sin(phase.toDouble()).toFloat()) +
                0.20f * abs(sin((phase * 2.3f).toDouble()).toFloat())
            currentLevel = currentLevel * 0.6f + (targetLevel * envelope) * 0.4f
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.5f
        val baseRadius = (h * 0.24f).coerceAtMost(48f)

        // 1) 소리 크기에 맞춰 왔다갔다하는 동심원 펄스 링 2개
        val outerR = baseRadius * (1.25f + currentLevel * 0.85f)
        val midR = baseRadius * (1.10f + currentLevel * 0.48f)
        canvas.drawCircle(cx, cy, outerR, outerRingPaint)
        canvas.drawCircle(cx, cy, midR, midRingPaint)

        // 2) 좌우 2D 사운드 웨이브 바 (각 3개씩)
        val barWidth = 8f
        val barGap = 16f
        for (i in 1..3) {
            val wave = abs(sin((phase + i * 0.7f).toDouble()).toFloat())
            val barH = (14f + currentLevel * 46f * wave).coerceAtMost(h * 0.65f)
            val leftX = cx - outerR - 18f - i * (barWidth + barGap)
            rect.set(leftX, cy - barH * 0.5f, leftX + barWidth, cy + barH * 0.5f)
            canvas.drawRoundRect(rect, 4f, 4f, barPaint)

            val rightX = cx + outerR + 18f + (i - 1) * (barWidth + barGap)
            rect.set(rightX, cy - barH * 0.5f, rightX + barWidth, cy + barH * 0.5f)
            canvas.drawRoundRect(rect, 4f, 4f, barPaint)
        }

        // 3) 중앙 마이크 코어 원 & 심플 2D 마이크 아이콘
        val coreScale = 1.0f + currentLevel * 0.12f
        canvas.drawCircle(cx, cy, baseRadius * coreScale, coreCirclePaint)

        val mw = baseRadius * 0.28f
        val mh = baseRadius * 0.42f
        rect.set(cx - mw, cy - mh, cx + mw, cy + mh * 0.35f)
        canvas.drawRoundRect(rect, mw, mw, micFillPaint)

        rect.set(cx - mw * 1.6f, cy - mh * 0.4f, cx + mw * 1.6f, cy + mh * 0.85f)
        canvas.drawArc(rect, 0f, 180f, false, micStrokePaint)
        canvas.drawLine(cx, cy + mh * 0.85f, cx, cy + mh * 1.25f, micStrokePaint)
    }
}
