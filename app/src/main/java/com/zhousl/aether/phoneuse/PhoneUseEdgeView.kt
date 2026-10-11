package com.zhousl.aether.phoneuse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Hardware-rendered light: a bottom-up bloom contracts into the physical screen edge. */
internal class PhoneUseEdgeView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val matrix = Matrix()
    private var gradient: SweepGradient? = null
    private val shader = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(EDGE_SHADER) else null
    private var radius = 28f * density
    private var born = SystemClock.uptimeMillis()
    private var awaitingFirstFrame = false
    private var running = false
    private var motion = true
    var onInsetsChanged: ((WindowInsets) -> Unit)? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        // The window is already hardware accelerated. A changing full-screen hardware layer
        // adds an offscreen texture allocation and another full-screen composite every frame.
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 31) {
                radius = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                    .mapNotNull { insets.getRoundedCorner(it)?.radius }.maxOrNull()?.toFloat() ?: radius
            }
            onInsetsChanged?.invoke(insets)
            insets
        }
    }

    fun start() {
        born = SystemClock.uptimeMillis()
        awaitingFirstFrame = true
        running = true
        motion = ValueAnimator.areAnimatorsEnabled()
        invalidate()
    }

    fun stop() { running = false }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradient = SweepGradient(w / 2f, h / 2f, intArrayOf(
            Color.rgb(255, 78, 142), Color.rgb(123, 74, 255), Color.rgb(25, 177, 255),
            Color.rgb(29, 202, 238), Color.rgb(255, 153, 54), Color.rgb(252, 71, 164), Color.rgb(255, 78, 142),
        ), null)
    }

    override fun onDraw(canvas: Canvas) {
        if (awaitingFirstFrame) {
            born = SystemClock.uptimeMillis()
            awaitingFirstFrame = false
        }
        val elapsed = if (motion) (SystemClock.uptimeMillis() - born) / 1000f else 3f
        if (Build.VERSION.SDK_INT >= 33 && shader != null && canvas.isHardwareAccelerated) {
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("density", density)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("time", elapsed)
            paint.shader = shader
            paint.style = Paint.Style.FILL
            // The shader is fully transparent beyond 110dp from the physical edge.
            // Draw four disjoint bands, preserving its coordinates and rounded-corner bloom.
            val bandX = min(max(110f * density, radius), width / 2f)
            val bandY = min(max(110f * density, radius), height / 2f)
            canvas.drawRect(0f, 0f, width.toFloat(), bandY, paint)
            canvas.drawRect(0f, height - bandY, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, bandY, bandX, height - bandY, paint)
            canvas.drawRect(width - bandX, bandY, width.toFloat(), height - bandY, paint)
        } else {
            // Bounded, allocation-free fallback for Android 8–12. No full-screen software blur.
            matrix.setRotate(elapsed * 12f, width / 2f, height / 2f)
            gradient?.setLocalMatrix(matrix)
            paint.shader = gradient
            paint.style = Paint.Style.STROKE
            val entry = (elapsed / .4f).coerceIn(0f, 1f)
            val bloom = if (elapsed < 1.1f) max(0f, sin(elapsed / 1.1f * Math.PI).toFloat()) else 0f
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            for (layer in 18 downTo 1) {
                paint.strokeWidth = (layer * 1.5f + bloom * layer * 2f) * density
                paint.alpha = (255 * entry * exp(-layer / 5f) * .13f).toInt()
                canvas.drawRoundRect(bounds, radius, radius, paint)
            }
            paint.strokeWidth = 3f * density
            paint.alpha = (240 * entry).toInt()
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
        if (running && motion) postInvalidateOnAnimation()
    }
}

// Rounded-rectangle distance gives a true edge-hugging halo, including under system bars.
// The activation front travels from the bottom; hue drifts slowly after the initial violet bloom.
private const val EDGE_SHADER = """
uniform float2 size;
uniform float density;
uniform float radius;
uniform float time;
float3 palette(float p) {
    float3 a = float3(1.0, .22, .55);
    float3 b = float3(.48, .20, 1.0);
    float3 c = float3(.06, .64, 1.0);
    float3 d = float3(.12, .80, .94);
    float3 e = float3(1.0, .49, .10);
    float t = fract(p) * 5.0;
    if (t < 1.0) return mix(a, b, smoothstep(0.0, 1.0, t));
    if (t < 2.0) return mix(b, c, smoothstep(1.0, 2.0, t));
    if (t < 3.0) return mix(c, d, smoothstep(2.0, 3.0, t));
    if (t < 4.0) return mix(d, e, smoothstep(3.0, 4.0, t));
    return mix(e, a, smoothstep(4.0, 5.0, t));
}
half4 main(float2 xy) {
    float2 q = abs(xy - size * .5) - (size * .5 - radius);
    float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float dist = -sdf / density;
    if (dist < 0.0 || dist > 110.0) return half4(0.0);
    float angle = atan(xy.y - size.y * .5, xy.x - size.x * .5);
    float perimeter = angle / 6.2831853;
    float travel = (1.0 - xy.y / size.y) * .60 + abs(xy.x / size.x - .5) * .16;
    float entry = smoothstep(travel, travel + .25, time);
    float bloom = sin(clamp(time / 1.15, 0.0, 1.0) * 3.1415927);
    float width = 4.0 + bloom * 23.0;
    float core = exp(-pow((dist - 1.2) / 1.1, 2.0));
    float halo = exp(-dist / width) * (.46 + bloom * .28);
    float aura = exp(-dist / (width * 2.0)) * (.07 + bloom * .15);
    float p = perimeter + .13 + time * .027 + sin(perimeter * 12.566 + time * .8) * .035;
    float3 color = palette(p);
    float3 violet = mix(float3(.56, .12, 1.0), float3(1.0, .16, .78), sin(angle * 2.0) * .5 + .5);
    color = mix(violet, color, smoothstep(.5, 1.35, time));
    color = mix(color, float3(1.0), core * .16);
    float alpha = clamp((core * .8 + halo + aura) * entry, 0.0, .98);
    return half4(color * alpha, alpha);
}
"""
