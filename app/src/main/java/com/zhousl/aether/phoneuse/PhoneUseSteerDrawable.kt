package com.zhousl.aether.phoneuse

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Small native marks so the inline composer shares the edge's color and weight. */
internal class PhoneUseSteerDrawable(private val kind: Kind) : Drawable() {
    enum class Kind { Orb, Close, Send }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = intArrayOf(0xFFFC5D7B.toInt(), 0xFF8770F4.toInt(), 0xFF4B9EFF.toInt(), 0xFFFE718C.toInt(), 0xFFFC5D7B.toInt())
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.shader = null
        paint.strokeWidth = 1.7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF88818B.toInt()
        when (kind) {
            Kind.Orb -> {
                paint.shader = SweepGradient(12f, 12f, palette, null)
                canvas.drawCircle(12f, 12f, 10f, paint)
                val path = android.graphics.Path()
                for (i in 0..100) {
                    val angle = i * Math.PI * 2 / 100
                    val r = 6.6f + 2.1f * sin(angle * 4)
                    val x = 12f + cos(angle).toFloat() * r.toFloat()
                    val y = 12f + sin(angle).toFloat() * r.toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            Kind.Close -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(9f, 9f, 15f, 15f, paint)
                canvas.drawLine(15f, 9f, 9f, 15f, paint)
            }
            Kind.Send -> {
                paint.color = 0xFF675770.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 12f, 10f, paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                canvas.drawLine(12f, 17f, 12f, 7f, paint)
                canvas.drawLine(8f, 11f, 12f, 7f, paint)
                canvas.drawLine(12f, 7f, 16f, 11f, paint)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
