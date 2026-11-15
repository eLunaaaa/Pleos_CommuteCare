/**
 * @file PloOceanBootView.kt
 * @brief Pleos Flo 앱 시작 시 바다 수면 물결과 파동을 그리는 부팅 스플래시 배경 캔버스 뷰.
 *
 * 동작 순서:
 * 1. 아이스 블루 & 화이트 톤온톤 그라데이션 배경 위에 3겹의 반투명 바다 물결(Sine Wave)을 렌더링한다.
 * 2. 상어 마스코트 '플로(Plo)'가 수면 위로 떠오르는 중심부에 동심원 물결 파동(Water Ripple)을 확산시킨다.
 * 3. 약 3초간의 부팅 시퀀스 동안 30fps 프레임 페이싱으로 부드럽게 애니메이션을 수행한다.
 */
package ai.pleos.commutecare.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class PloOceanBootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var phase = 0f
    private var lastDrawNanos = 0L
    private var isRunning = false

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wavePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 125, 211, 252) // Light Sky Wave
    }
    private val wavePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 56, 189, 248) // Crystal Ocean Wave
    }
    private val wavePaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(145, 37, 99, 235)  // Deep Pleos Blue Wave
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(160, 56, 189, 248)
    }

    private val wavePath = Path()
    private var cachedH = -1f

    /**
     * @brief 뷰가 윈도우에 연결될 때 바다 물결 애니메이션을 시작한다.
     * @return Unit
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isRunning = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * @brief 뷰가 윈도우에서 분리될 때 애니메이션 콜백을 해제한다.
     * @return Unit
     */
    override fun onDetachedFromWindow() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    /**
     * @brief 부팅 오버레이가 다시 표시될 때 바다 물결 애니메이션을 재시작한다.
     * @return Unit
     */
    fun startAnimation() {
        if (!isRunning) {
            isRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * @brief 부팅 오버레이가 숨겨질 때 애니메이션을 즉시 중지하여 CPU 자원을 절약한다.
     * @return Unit
     */
    fun stopAnimation() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /**
     * @brief 프레임 콜백마다 물결 위상을 갱신한다.
     * @param frameTimeNanos VSync 타임스탬프 (ns)
     * @return Unit
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning || visibility != VISIBLE) return
        if (frameTimeNanos - lastDrawNanos >= 32_000_000L) {
            lastDrawNanos = frameTimeNanos
            phase = (phase + 0.045f) % (2f * PI.toFloat())
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * @brief 바다 수면 물결과 수면 파동을 캔버스에 렌더링한다.
     * @param canvas Android Canvas
     * @return Unit
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (cachedH != h) {
            cachedH = h
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#F8FBFF"),
                Color.parseColor("#D8ECFF"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        // 수면 위 동심원 파동 (상어가 올라오는 위치 주변)
        val cx = w * 0.5f
        val cy = h * 0.46f
        val pulse = (sin(phase.toDouble()).toFloat() * 0.5f + 0.5f)
        ripplePaint.alpha = (90 + pulse * 80).toInt()
        canvas.drawOval(cx - 190f - pulse * 35f, cy + 55f, cx + 190f + pulse * 35f, cy + 125f + pulse * 12f, ripplePaint)
        ripplePaint.alpha = (55 + (1f - pulse) * 70).toInt()
        canvas.drawOval(cx - 260f - pulse * 45f, cy + 45f, cx + 260f + pulse * 45f, cy + 140f + pulse * 18f, ripplePaint)

        // 3겹의 바다 물결 레이어
        drawSineWave(canvas, w, h, baseY = h * 0.56f, amp = 22f, freq = 1.8f, phaseShift = phase, paint = wavePaint1)
        drawSineWave(canvas, w, h, baseY = h * 0.62f, amp = 26f, freq = 1.4f, phaseShift = phase + 1.3f, paint = wavePaint2)
        drawSineWave(canvas, w, h, baseY = h * 0.69f, amp = 20f, freq = 2.1f, phaseShift = phase + 2.5f, paint = wavePaint3)
    }

    private fun drawSineWave(
        canvas: Canvas,
        w: Float,
        h: Float,
        baseY: Float,
        amp: Float,
        freq: Float,
        phaseShift: Float,
        paint: Paint
    ) {
        wavePath.reset()
        wavePath.moveTo(0f, h)
        val steps = 32
        for (i in 0..steps) {
            val x = w * i / steps
            val angle = (i.toFloat() / steps) * freq * 2f * PI.toFloat() + phaseShift
            val y = baseY + sin(angle.toDouble()).toFloat() * amp
            wavePath.lineTo(x, y)
        }
        wavePath.lineTo(w, h)
        wavePath.close()
        canvas.drawPath(wavePath, paint)
    }
}
