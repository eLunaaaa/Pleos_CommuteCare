/**
 * @file PloOceanBootView.kt
 * @brief Pleos Flo 앱 시작 시 미니멀한 2D 선과 '첨벙(Splash)' 물방울 애니메이션을 그리는 부팅 뷰.
 *
 * 동작 순서:
 * 1. 깨끗한 화이트-아이스블루 단색 배경(#F8FBFF)을 유지한다.
 * 2. 최대한 단순한 2D 곡선 2줄로 수면을 표현한다.
 * 3. 2D 상어 마스코트가 수면 위로 올라올 때 좌우로 튀어오르는 2D 물방울('첨벙' 효과)을 렌더링한다.
 */
package ai.pleos.commutecare.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PloOceanBootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var phase = 0f
    private var splashProgress = 0f
    private var lastDrawNanos = 0L
    private var isRunning = false

    private val linePaintPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#38BDF8")
    }

    private val linePaintSecondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#93C5FD")
    }

    private val dropletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38BDF8")
    }

    private val wavePath = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    /**
     * @brief 부팅 오버레이 표시 시 2D 수면 선 및 첨벙 물방울 애니메이션을 시작한다.
     * @return Unit
     */
    fun startAnimation() {
        splashProgress = 0f
        if (!isRunning) {
            isRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * @brief 부팅 오버레이 종료 시 애니메이션을 중지한다.
     * @return Unit
     */
    fun stopAnimation() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning || visibility != VISIBLE) return
        if (frameTimeNanos - lastDrawNanos >= 32_000_000L) {
            lastDrawNanos = frameTimeNanos
            phase = (phase + 0.08f) % (2f * PI.toFloat())
            splashProgress = (splashProgress + 0.025f).coerceAtMost(1.2f)
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1) 미니멀 단색 배경
        canvas.drawColor(Color.parseColor("#F8FBFF"))

        val cx = w * 0.5f
        val waterY = h * 0.52f

        // 2) 단순한 2D 수면 물결 선 2줄
        drawSimpleWaveLine(canvas, cx - 280f, cx + 280f, waterY, amp = 10f, phaseShift = phase, paint = linePaintPrimary)
        drawSimpleWaveLine(canvas, cx - 200f, cx + 200f, waterY + 26f, amp = 7f, phaseShift = phase + 1.5f, paint = linePaintSecondary)

        // 3) '첨벙!' 2D 물방울 6개 (상어가 올라올 때 포물선으로 톡 튀는 단순 2D 원/선)
        val angles = floatArrayOf(-145f, -125f, -108f, -72f, -55f, -35f)
        val t = (splashProgress % 1.0f)
        val alpha = ((1f - t) * 235f).toInt().coerceIn(0, 235)
        dropletPaint.alpha = alpha
        for (i in angles.indices) {
            val rad = Math.toRadians(angles[i].toDouble())
            val dist = 95f + t * 130f
            val gravity = t * t * 75f
            val dx = cx + cos(rad).toFloat() * dist
            val dy = (waterY - 40f) + sin(rad).toFloat() * dist + gravity
            val radius = if (i % 2 == 0) 7.5f else 5.5f
            canvas.drawCircle(dx, dy, radius, dropletPaint)
        }
    }

    private fun drawSimpleWaveLine(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        baseY: Float,
        amp: Float,
        phaseShift: Float,
        paint: Paint
    ) {
        wavePath.reset()
        val steps = 24
        val span = endX - startX
        for (i in 0..steps) {
            val x = startX + span * (i.toFloat() / steps)
            val y = baseY + sin((i.toFloat() / steps) * 2f * PI.toFloat() + phaseShift) * amp
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        canvas.drawPath(wavePath, paint)
    }
}
