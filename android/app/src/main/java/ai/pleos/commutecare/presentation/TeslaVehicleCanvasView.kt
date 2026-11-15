/**
 * @file TeslaVehicleCanvasView.kt
 * @brief 테슬라 스타일의 투명 글래스모픽 차량 실루엣 및 실시간 공조·주행 애니메이션 뷰.
 *
 * 동작 순서:
 * 1. 반투명 화이트 & 블루 톤온톤 기반의 세단 탑다운(Top-Down) 투명 차체 및 파노라마 글래스 루프를 렌더링한다.
 * 2. 목표 온도(targetTempC)와 프리컨디셔닝 상태(isPreconditioning)에 따라 실내 공조 기류(Airflow) 웨이브와
 *    운전석 히팅/쿨링 글로우를 실시간 60fps 애니메이션으로 표현한다.
 * 3. 차속(speedKmh)에 연동된 도로 주행 가이드 라인 및 전면 LED DRL 펄스 효과를 함께 표시한다.
 */
package ai.pleos.commutecare.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class TeslaVehicleCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var targetTempC: Float = 22.5f
    private var displayTempC: Float = 22.5f
    private var isPreconditioning: Boolean = true
    private var speedKmh: Float = 0f
    private var displaySpeedKmh: Float = 0f

    private var animPhase: Float = 0f
    private var lastFrameNanos: Long = 0L
    private var lastDrawNanos: Long = 0L
    private var isAnimating: Boolean = false
    private var cachedBodyShaderWidth: Float = -1f
    private var cachedBodyShaderHeight: Float = -1f

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(65, 59, 130, 246)
    }

    private val radarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(45, 37, 99, 235)
    }

    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(235, 255, 255, 255)
    }

    private val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 191, 219, 254)
    }

    private val glassStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = Color.argb(190, 59, 130, 246)
    }

    private val cabinGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val airflowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4.5f
    }

    private val lightBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5.5f
    }

    private val seatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bodyPath = Path()
    private val glassPath = Path()
    private val hoodLineLeft = Path()
    private val hoodLineRight = Path()
    private val airflowPath = Path()
    private val tempRect = RectF()

    /**
     * @brief 차량 공조·주행 텔레메트리 상태를 갱신하여 애니메이션 색상과 강도를 즉시 반영한다.
     * @param tempC 현재 목표 실내 온도 (섭씨)
     * @param preconditioningActive 프리컨디셔닝 가동 여부
     * @param currentSpeedKmh 현재 주행 속도 (km/h)
     * @return Unit
     */
    fun updateTelemetry(
        tempC: Float,
        preconditioningActive: Boolean,
        currentSpeedKmh: Float
    ) {
        targetTempC = tempC
        isPreconditioning = preconditioningActive
        speedKmh = currentSpeedKmh
        invalidate()
    }

    /**
     * @brief 뷰가 윈도우에 연결될 때 디스플레이 VSync(고주사율) 프레임 콜백을 시작한다.
     * @return Unit
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAnimating = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * @brief 뷰가 윈도우에서 분리될 때 VSync 프레임 콜백을 해제하여 누수를 방지한다.
     * @return Unit
     */
    override fun onDetachedFromWindow() {
        isAnimating = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    /**
     * @brief 디스플레이 주사율에 동기화된 매 프레임마다 위상과 텔레메트리 보간값을 갱신한다.
     * @param frameTimeNanos 현재 VSync 프레임 타임스탬프 (ns)
     * @return Unit
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (!isAnimating) return
        if (lastFrameNanos != 0L) {
            val dtSec = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            animPhase = (animPhase + dtSec * 0.38f) % 1f
            // 부드러운 고주사율 보간(Lerp)
            val lerpFactor = (dtSec * 8f).coerceIn(0f, 1f)
            displayTempC += (targetTempC - displayTempC) * lerpFactor
            displaySpeedKmh += (speedKmh - displaySpeedKmh) * lerpFactor
            // 소프트웨어 렌더러(에뮬레이터) ANR 방지를 위해 최소 32ms(~30fps) 간격으로 갱신
            if (frameTimeNanos - lastDrawNanos >= 32_000_000L) {
                lastDrawNanos = frameTimeNanos
                invalidate()
            }
        } else {
            lastDrawNanos = frameTimeNanos
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * @brief 투명 차체 실루엣, 주행 레이더, 공조 기류 웨이브를 캔버스에 그린다.
     * @param canvas Android Canvas 인스턴스
     * @return Unit
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.5f
        val carW = (w * 0.44f).coerceAtMost(h * 0.42f)
        val carH = (h * 0.78f).coerceAtMost(carW * 2.15f)

        val left = cx - carW * 0.5f
        val right = cx + carW * 0.5f
        val top = cy - carH * 0.5f
        val bottom = cy + carH * 0.5f

        drawRadarAndRoadGuides(canvas, cx, cy, carW, carH)
        drawTransparentCarBody(canvas, cx, cy, left, top, right, bottom, carW, carH)
        drawCabinThermalGlow(canvas, cx, cy, carW, carH)
        drawAirflowWaves(canvas, cx, top, carW, carH)
        drawLightBars(canvas, cx, top, bottom, carW)
    }

    private fun drawRadarAndRoadGuides(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        carW: Float,
        carH: Float
    ) {
        // 1) 동심원 레이더 펄스 (테슬라 오토파일럿 감성)
        val pulse = sin(animPhase * 2f * PI).toFloat() * 0.5f + 0.5f
        radarRingPaint.alpha = (25 + pulse * 35).toInt()
        canvas.drawCircle(cx, cy, carH * (0.44f + pulse * 0.04f), radarRingPaint)
        radarRingPaint.alpha = (18 + (1f - pulse) * 28).toInt()
        canvas.drawCircle(cx, cy, carH * (0.54f + pulse * 0.03f), radarRingPaint)

        // 2) 좌우 주행 차선 가이드 (차속이 높을수록 스크롤 속도 증가)
        val laneOffset = (animPhase * 120f * (1f + displaySpeedKmh / 40f)) % 60f
        roadPaint.pathEffect = DashPathEffect(floatArrayOf(28f, 32f), -laneOffset)
        val laneHalfSpan = carW * 0.88f
        canvas.drawLine(cx - laneHalfSpan, cy - carH * 0.55f, cx - laneHalfSpan, cy + carH * 0.55f, roadPaint)
        canvas.drawLine(cx + laneHalfSpan, cy - carH * 0.55f, cx + laneHalfSpan, cy + carH * 0.55f, roadPaint)
        roadPaint.pathEffect = null
    }

    private fun drawTransparentCarBody(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        carW: Float,
        carH: Float
    ) {
        // 반투명 화이트-블루 차체 외곽선 (부드러운 테이퍼드 세단 실루엣)
        bodyPath.reset()
        bodyPath.moveTo(cx, top)
        bodyPath.cubicTo(
            left + carW * 0.12f, top,
            left, top + carH * 0.14f,
            left, top + carH * 0.28f
        )
        bodyPath.lineTo(left, bottom - carH * 0.18f)
        bodyPath.cubicTo(
            left, bottom - carH * 0.04f,
            left + carW * 0.14f, bottom,
            cx, bottom
        )
        bodyPath.cubicTo(
            right - carW * 0.14f, bottom,
            right, bottom - carH * 0.04f,
            right, bottom - carH * 0.18f
        )
        bodyPath.lineTo(right, top + carH * 0.28f)
        bodyPath.cubicTo(
            right, top + carH * 0.14f,
            right - carW * 0.12f, top,
            cx, top
        )
        bodyPath.close()

        if (cachedBodyShaderWidth != carW || cachedBodyShaderHeight != carH) {
            cachedBodyShaderWidth = carW
            cachedBodyShaderHeight = carH
            bodyFillPaint.shader = LinearGradient(
                left, top, right, bottom,
                Color.argb(175, 255, 255, 255),
                Color.argb(135, 219, 234, 254),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(bodyPath, bodyFillPaint)
        canvas.drawPath(bodyPath, bodyStrokePaint)

        // 사이드 미러 (좌/우)
        val mirrorY = top + carH * 0.30f
        tempRect.set(left - carW * 0.09f, mirrorY, left + carW * 0.02f, mirrorY + carH * 0.055f)
        canvas.drawRoundRect(tempRect, 8f, 8f, bodyFillPaint)
        canvas.drawRoundRect(tempRect, 8f, 8f, bodyStrokePaint)

        tempRect.set(right - carW * 0.02f, mirrorY, right + carW * 0.09f, mirrorY + carH * 0.055f)
        canvas.drawRoundRect(tempRect, 8f, 8f, bodyFillPaint)
        canvas.drawRoundRect(tempRect, 8f, 8f, bodyStrokePaint)

        // 후드 캐릭터 라인
        hoodLineLeft.reset()
        hoodLineLeft.moveTo(cx - carW * 0.24f, top + carH * 0.08f)
        hoodLineLeft.quadTo(cx - carW * 0.28f, top + carH * 0.18f, cx - carW * 0.32f, top + carH * 0.27f)
        canvas.drawPath(hoodLineLeft, glassStrokePaint)

        hoodLineRight.reset()
        hoodLineRight.moveTo(cx + carW * 0.24f, top + carH * 0.08f)
        hoodLineRight.quadTo(cx + carW * 0.28f, top + carH * 0.18f, cx + carW * 0.32f, top + carH * 0.27f)
        canvas.drawPath(hoodLineRight, glassStrokePaint)

        // 파노라마 글래스 루프 & 앞/뒤 유리
        val glassL = left + carW * 0.14f
        val glassR = right - carW * 0.14f
        val glassT = top + carH * 0.27f
        val glassB = bottom - carH * 0.14f
        glassPath.reset()
        glassPath.moveTo(cx, glassT)
        glassPath.cubicTo(glassL + carW * 0.05f, glassT, glassL, glassT + carH * 0.08f, glassL, glassT + carH * 0.16f)
        glassPath.lineTo(glassL + carW * 0.03f, glassB - carH * 0.10f)
        glassPath.cubicTo(glassL + carW * 0.05f, glassB, cx - carW * 0.12f, glassB, cx, glassB)
        glassPath.cubicTo(cx + carW * 0.12f, glassB, glassR - carW * 0.05f, glassB, glassR - carW * 0.03f, glassB - carH * 0.10f)
        glassPath.lineTo(glassR, glassT + carH * 0.16f)
        glassPath.cubicTo(glassR, glassT + carH * 0.08f, glassR - carW * 0.05f, glassT, cx, glassT)
        glassPath.close()

        canvas.drawPath(glassPath, glassFillPaint)
        canvas.drawPath(glassPath, glassStrokePaint)

        // 운전석 / 조수석 투명 시트 윤곽
        val seatY = cy - carH * 0.02f
        val seatW = carW * 0.22f
        val seatH = carH * 0.14f
        val thermalColor = resolveThermalColor()
        seatPaint.color = Color.argb(if (isPreconditioning) 85 else 45, Color.red(thermalColor), Color.green(thermalColor), Color.blue(thermalColor))

        // 운전석(좌)
        tempRect.set(cx - carW * 0.30f, seatY, cx - carW * 0.30f + seatW, seatY + seatH)
        canvas.drawRoundRect(tempRect, 14f, 14f, seatPaint)
        canvas.drawRoundRect(tempRect, 14f, 14f, glassStrokePaint)

        // 조수석(우)
        tempRect.set(cx + carW * 0.08f, seatY, cx + carW * 0.08f + seatW, seatY + seatH)
        canvas.drawRoundRect(tempRect, 14f, 14f, seatPaint)
        canvas.drawRoundRect(tempRect, 14f, 14f, glassStrokePaint)
    }

    private fun drawCabinThermalGlow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        carW: Float,
        carH: Float
    ) {
        if (!isPreconditioning) return
        val baseColor = resolveThermalColor()
        val pulse = sin(animPhase * 2f * PI).toFloat() * 0.5f + 0.5f
        val alphaCenter = (55 + pulse * 45).toInt()
        cabinGlowPaint.shader = RadialGradient(
            cx, cy + carH * 0.02f, carW * 0.45f,
            Color.argb(alphaCenter, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + carH * 0.02f, carW * 0.45f, cabinGlowPaint)
    }

    private fun drawAirflowWaves(
        canvas: Canvas,
        cx: Float,
        top: Float,
        carW: Float,
        carH: Float
    ) {
        if (!isPreconditioning) return
        val baseColor = resolveThermalColor()
        val startY = top + carH * 0.31f
        val endY = top + carH * 0.64f
        val spanY = endY - startY

        // 좌/중/우 3갈래 공조 기류 스트림
        val offsetsX = floatArrayOf(-carW * 0.17f, 0f, carW * 0.17f)
        for (idx in offsetsX.indices) {
            val streamShift = (animPhase + idx * 0.25f) % 1f
            airflowPath.reset()
            val baseX = cx + offsetsX[idx]
            val segStart = startY + streamShift * spanY * 0.35f
            val segEnd = (segStart + spanY * 0.48f).coerceAtMost(endY)
            val steps = 12
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val y = segStart + (segEnd - segStart) * t
                val waveX = baseX + sin((t * 2f + animPhase * 3f) * PI).toFloat() * (carW * 0.035f)
                if (s == 0) airflowPath.moveTo(waveX, y) else airflowPath.lineTo(waveX, y)
            }
            val fadeAlpha = (sin(streamShift * PI).toFloat() * 210f).toInt().coerceIn(30, 220)
            airflowPaint.color = Color.argb(
                fadeAlpha,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
            canvas.drawPath(airflowPath, airflowPaint)
        }
    }

    private fun drawLightBars(
        canvas: Canvas,
        cx: Float,
        top: Float,
        bottom: Float,
        carW: Float
    ) {
        val pulse = sin(animPhase * 2f * PI).toFloat() * 0.5f + 0.5f
        // 전면 시그니처 LED DRL (블루-화이트)
        lightBarPaint.color = Color.argb((170 + pulse * 85).toInt(), 56, 189, 248)
        canvas.drawLine(
            cx - carW * 0.31f, top + 12f,
            cx - carW * 0.08f, top + 6f,
            lightBarPaint
        )
        canvas.drawLine(
            cx + carW * 0.08f, top + 6f,
            cx + carW * 0.31f, top + 12f,
            lightBarPaint
        )

        // 후면 일자형 테일램프 (은은한 블루-코랄 악센트)
        lightBarPaint.color = Color.argb(175, 59, 130, 246)
        canvas.drawLine(
            cx - carW * 0.28f, bottom - 8f,
            cx + carW * 0.28f, bottom - 8f,
            lightBarPaint
        )
    }

    private fun resolveThermalColor(): Int {
        return when {
            displayTempC <= 21.0f -> Color.rgb(14, 165, 233)   // Cool Ice Blue
            displayTempC >= 24.0f -> Color.rgb(245, 158, 11)   // Warm Amber Glow
            else -> Color.rgb(37, 99, 235)                    // Balanced Pleos Blue
        }
    }
}
