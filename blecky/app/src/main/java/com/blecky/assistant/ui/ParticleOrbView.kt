package com.blecky.assistant.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
 */
class ParticleOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class Particle(val x: Float, val y: Float, val z: Float, val phase: Float, val speed: Float)

    private val particles = List(COUNT) { i ->
        // Even spread over a sphere (Fibonacci lattice).
        val y = 1f - 2f * (i + 0.5f) / COUNT
        val r = sqrt(1f - y * y)
        val theta = i * GOLDEN_ANGLE
        Particle(r * cos(theta), y, r * sin(theta), Random.nextFloat() * 6.28f, 0.6f + Random.nextFloat())
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = RectF()
    private val density = resources.displayMetrics.density
    private val ringGlow = BlurMaskFilter(density * 2f, BlurMaskFilter.Blur.SOLID)

    private var rotation = 0f
    private var ringAngle = 0f
    private var level = 0f
    private var time = 0f
    private var lastFrameNanos = 0L
    private var red = 0f
    private var green = 229f
    private var blue = 255f
    private var glowKey = 0

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = now
        time += dt

        val mode = VoiceState.mode
        level += (VoiceState.level - level) * min(1f, dt * 10f)
        val (tr, tg, tb) = colorFor(mode)
        val blend = min(1f, dt * 4f)
        red += (tr - red) * blend
        green += (tg - green) * blend
        blue += (tb - blue) * blend

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

        drawGlow(canvas, cx, cy, radius)
        drawRings(canvas, cx, cy, radius, mode)
        drawParticles(canvas, cx, cy, radius, mode)

        if (isShown) postInvalidateOnAnimation()
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, mode: Mode) {
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        val tilt = 0.35f
        val cosT = cos(tilt)
        val sinT = sin(tilt)
        val color = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
        for (p in particles) {
            // Waves across the surface follow the voice.
            val wave = when (mode) {
                Mode.LISTENING, Mode.SPEAKING -> 1f + level * 0.35f * sin(p.phase + time * 7f * p.speed)
                Mode.THINKING -> 1f + 0.06f * sin(p.phase + time * 4f)
                else -> 1f + 0.015f * sin(p.phase + time * p.speed)
            }
            val x1 = p.x * cosR + p.z * sinR
            val z1 = -p.x * sinR + p.z * cosR
            val y2 = p.y * cosT - z1 * sinT
            val z2 = p.y * sinT + z1 * cosT
            val perspective = 2.6f / (2.6f - z2)
            val px = cx + x1 * radius * wave * perspective
            val py = cy + y2 * radius * wave * perspective
            val depth = (z2 + 1f) / 2f
            dotPaint.color = color
            dotPaint.alpha = (40 + 215 * depth).toInt()
            canvas.drawCircle(px, py, density * (0.7f + 1.6f * depth) * perspective, dotPaint)
        }
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val key = (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt() xor radius.toInt()
        if (key != glowKey) {
            glowKey = key
            val core = Color.argb(110, red.toInt(), green.toInt(), blue.toInt())
            glowPaint.shader = RadialGradient(cx, cy, radius * 1.5f, intArrayOf(core, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, radius * 1.5f, glowPaint)
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, mode: Mode) {
        val color = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
        ringPaint.color = color
        haloPaint.color = color
        haloPaint.alpha = 50
        haloPaint.strokeWidth = density
        canvas.drawCircle(cx, cy, radius * 1.18f, haloPaint)

        // Two dashed HUD rings turning in opposite directions.
        listOf(1.32f to 1f, 1.45f to -0.6f).forEachIndexed { index, (scale, direction) ->
            val r = radius * scale
            arc.set(cx - r, cy - r, cx + r, cy + r)
            ringPaint.strokeWidth = density * (if (index == 0) 2.2f else 1.2f)
            ringPaint.alpha = if (mode == Mode.OFF) 50 else 150 - index * 50
            ringPaint.maskFilter = if (index == 0) ringGlow else null
            val segments = if (index == 0) 6 else 24
            val sweep = 360f / segments
            for (s in 0 until segments) {
                canvas.drawArc(arc, ringAngle * direction + s * sweep, sweep * (if (index == 0) 0.6f else 0.3f), false, ringPaint)
            }
        }
    }

    private fun colorFor(mode: Mode): Triple<Float, Float, Float> = when (mode) {
        Mode.OFF -> Triple(70f, 100f, 130f)
        Mode.WAITING -> Triple(0f, 200f, 255f)
        Mode.LISTENING -> Triple(0f, 255f, 210f)
        Mode.THINKING -> Triple(170f, 120f, 255f)
        Mode.SPEAKING -> Triple(120f, 235f, 255f)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrameNanos = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    companion object {
        private const val COUNT = 650
        private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()
    }
}
