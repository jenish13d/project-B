package com.blecky.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.blecky.assistant.voice.VoiceState
import com.blecky.assistant.voice.VoiceState.Mode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A HUD-style sphere of glowing particles that breathes while waiting, ripples with your voice
 * while listening, swirls while thinking and pulses with Blecky's voice while she speaks.
 *
 * Drawn to keep the phone cool: particles go out in a few batched drawPoints calls instead of one
 * call each, nothing is allocated per frame, and the frame rate drops to 30 fps (15 when idle) and
 * stops whenever the view can't be seen.
 */
class ParticleOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val baseX = FloatArray(COUNT)
    private val baseY = FloatArray(COUNT)
    private val baseZ = FloatArray(COUNT)
    private val phase = FloatArray(COUNT)
    private val speed = FloatArray(COUNT)

    init {
        // Even spread over a sphere (Fibonacci lattice).
        for (i in 0 until COUNT) {
            val y = 1f - 2f * (i + 0.5f) / COUNT
            val r = sqrt(1f - y * y)
            val theta = i * GOLDEN_ANGLE
            baseX[i] = r * cos(theta)
            baseY[i] = y
            baseZ[i] = r * sin(theta)
            phase[i] = Random.nextFloat() * 6.28f
            speed[i] = 0.6f + Random.nextFloat()
        }
    }

    // Particles are sorted into depth layers; each layer is one drawPoints call with its own size and alpha.
    private val layerPoints = Array(LAYERS) { FloatArray(COUNT * 2) }
    private val layerCount = IntArray(LAYERS)
    private val layerPaints = Array(LAYERS) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = RectF()
    private val density = resources.displayMetrics.density

    private var rotation = 0f
    private var ringAngle = 0f
    private var level = 0f
    private var time = 0f
    private var lastFrameMs = 0L
    private var red = 0f
    private var green = 229f
    private var blue = 255f
    private var glowColor = 0
    private var glowRadius = 0f
    private var running = false

    private val frame = Runnable { invalidate() }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0.033f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.1f)
        lastFrameMs = now
        time += dt

        val mode = VoiceState.mode
        level += (VoiceState.level - level) * min(1f, dt * 10f)
        val blend = min(1f, dt * 4f)
        red += (targetRed(mode) - red) * blend
        green += (targetGreen(mode) - green) * blend
        blue += (targetBlue(mode) - blue) * blend

        rotation += dt * when (mode) {
            Mode.OFF -> 0.08f
            Mode.WAITING -> 0.2f
            Mode.LISTENING -> 0.45f
            Mode.THINKING -> 1.8f
            Mode.SPEAKING -> 0.6f
        }
        ringAngle += dt * if (mode == Mode.THINKING) 120f else 30f

        val cx = width / 2f
        val cy = height / 2f
        val breathe = 1f + 0.03f * sin(time * 1.6f)
        val radius = min(width, height) * 0.30f * breathe * (1f + 0.22f * level)
        val color = Color.rgb(red.toInt(), green.toInt(), blue.toInt())

        drawGlow(canvas, cx, cy, radius)
        drawRings(canvas, cx, cy, radius, mode, color)
        drawParticles(canvas, cx, cy, radius, mode, color)

        scheduleNextFrame(mode)
    }

    private fun scheduleNextFrame(mode: Mode) {
        if (!running) return
        val idle = mode == Mode.OFF || mode == Mode.WAITING
        postOnAnimationDelayed(frame, if (idle) IDLE_FRAME_MS else ACTIVE_FRAME_MS)
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, mode: Mode, color: Int) {
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        layerCount.fill(0)
        for (i in 0 until COUNT) {
            // Waves across the surface follow the voice.
            val wave = when (mode) {
                Mode.LISTENING, Mode.SPEAKING -> 1f + level * 0.35f * sin(phase[i] + time * 7f * speed[i])
                Mode.THINKING -> 1f + 0.06f * sin(phase[i] + time * 4f)
                else -> 1f + 0.015f * sin(phase[i] + time * speed[i])
            }
            val x1 = baseX[i] * cosR + baseZ[i] * sinR
            val z1 = -baseX[i] * sinR + baseZ[i] * cosR
            val y2 = baseY[i] * COS_TILT - z1 * SIN_TILT
            val z2 = baseY[i] * SIN_TILT + z1 * COS_TILT
            val perspective = 2.6f / (2.6f - z2)
            val layer = ((z2 + 1f) / 2f * LAYERS).toInt().coerceIn(0, LAYERS - 1)
            val n = layerCount[layer]
            layerPoints[layer][n] = cx + x1 * radius * wave * perspective
            layerPoints[layer][n + 1] = cy + y2 * radius * wave * perspective
            layerCount[layer] = n + 2
        }
        for (layer in 0 until LAYERS) {
            if (layerCount[layer] == 0) continue
            val depth = (layer + 0.5f) / LAYERS
            val paint = layerPaints[layer]
            paint.color = color
            paint.alpha = (40 + 215 * depth).toInt()
            // Diameter of the old per-particle circles at this depth.
            paint.strokeWidth = 2f * density * (0.7f + 1.6f * depth) * (2.6f / (2.6f - (depth * 2f - 1f)))
            canvas.drawPoints(layerPoints[layer], 0, layerCount[layer], paint)
        }
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // The gradient is built at one size and scaled; rebuilt only when the colour visibly changes.
        val quantized = Color.rgb(red.toInt() and 0xF8, green.toInt() and 0xF8, blue.toInt() and 0xF8)
        val base = min(width, height) * 0.30f * 1.5f
        if (quantized != glowColor || base != glowRadius) {
            glowColor = quantized
            glowRadius = base
            val core = Color.argb(110, Color.red(quantized), Color.green(quantized), Color.blue(quantized))
            glowPaint.shader = RadialGradient(0f, 0f, base, intArrayOf(core, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        val scale = radius * 1.5f / base
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, base, glowPaint)
        canvas.restore()
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, mode: Mode, color: Int) {
        haloPaint.color = color
        haloPaint.alpha = 50
        haloPaint.strokeWidth = density
        canvas.drawCircle(cx, cy, radius * 1.18f, haloPaint)

        // Two dashed HUD rings turning in opposite directions.
        for (index in 0..1) {
            val r = radius * if (index == 0) 1.32f else 1.45f
            val direction = if (index == 0) 1f else -0.6f
            arc.set(cx - r, cy - r, cx + r, cy + r)
            val segments = if (index == 0) 6 else 24
            val sweep = 360f / segments
            val dash = sweep * if (index == 0) 0.6f else 0.3f
            val alpha = if (mode == Mode.OFF) 50 else 150 - index * 50
            ringPaint.color = color
            if (index == 0) {
                // A wide faint stroke under the ring gives the soft glow a blur filter used to, at a fraction of the cost.
                ringPaint.strokeWidth = density * 6f
                ringPaint.alpha = alpha / 4
                for (s in 0 until segments) canvas.drawArc(arc, ringAngle * direction + s * sweep, dash, false, ringPaint)
            }
            ringPaint.strokeWidth = density * if (index == 0) 2.2f else 1.2f
            ringPaint.alpha = alpha
            for (s in 0 until segments) canvas.drawArc(arc, ringAngle * direction + s * sweep, dash, false, ringPaint)
        }
    }

    private fun targetRed(mode: Mode) = when (mode) {
        Mode.OFF -> 70f
        Mode.THINKING -> 170f
        Mode.SPEAKING -> 120f
        else -> 0f
    }

    private fun targetGreen(mode: Mode) = when (mode) {
        Mode.OFF -> 100f
        Mode.WAITING -> 200f
        Mode.LISTENING -> 255f
        Mode.THINKING -> 120f
        Mode.SPEAKING -> 235f
    }

    private fun targetBlue(mode: Mode) = when (mode) {
        Mode.OFF -> 130f
        Mode.LISTENING -> 210f
        else -> 255f
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setRunning(visibility == VISIBLE && isShown)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        setRunning(isShown && windowVisibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        super.onDetachedFromWindow()
    }

    /** Animates only while someone can see it; nothing runs behind other screens or with the screen off. */
    private fun setRunning(run: Boolean) {
        if (run == running) return
        running = run
        removeCallbacks(frame)
        lastFrameMs = 0L
        if (run) invalidate()
    }

    companion object {
        private const val COUNT = 650
        private const val LAYERS = 6
        private const val ACTIVE_FRAME_MS = 33L
        private const val IDLE_FRAME_MS = 66L
        private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()
        private val COS_TILT = cos(0.35f)
        private val SIN_TILT = sin(0.35f)
    }
}
