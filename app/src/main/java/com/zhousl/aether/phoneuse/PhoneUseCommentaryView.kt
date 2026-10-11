package com.zhousl.aether.phoneuse

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Only this small text surface uses software rasterization, for per-run blur on older Android. */
internal class PhoneUseCommentaryView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 35, 40)
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val blur = (0..12).map { if (it == 0) null else BlurMaskFilter(it * dp / 3f, BlurMaskFilter.Blur.NORMAL) }
    private val easing = PathInterpolator(.22f, .8f, .18f, 1f)
    private var messageId = ""
    private var value = ""
    private var layout: StaticLayout? = null
    private var outgoing: StaticLayout? = null
    private var layoutWidth = 0
    private var changedAt = 0L
    private var scrollFrom = 0f
    private var scrollTo = 0f
    private var sizeAnimator: ValueAnimator? = null
    private var animatedHeight = 0
    private val runs = mutableListOf<RevealRun>()
    private var motion = true
    var onHeightChanged: (() -> Unit)? = null

    private data class RevealRun(val from: Int, val to: Int, val born: Long)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setCommentary(id: String, text: String) {
        val normalized = text.replace(Regex("[\\t\\r ]+"), " ").trim().takeLast(6000)
        if (id == messageId && normalized == value) return
        motion = ValueAnimator.areAnimatorsEnabled()
        val now = SystemClock.uptimeMillis()
        val append = id == messageId && normalized.startsWith(value) && value.isNotEmpty()
        if (!append) {
            changedAt = now
            outgoing = layout?.let { previous ->
                val text = SpannableString(previous.text.toString())
                text.setSpan(object : CharacterStyle() {
                    override fun updateDrawState(tp: TextPaint) {
                        val t = ((SystemClock.uptimeMillis() - changedAt) / 440f).coerceIn(0f, 1f)
                        tp.maskFilter = blur[(easing.getInterpolation(t) * 12).roundToInt()]
                    }
                }, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                StaticLayout.Builder.obtain(text, 0, text.length, paint, previous.width)
                    .setAlignment(previous.alignment).setIncludePad(false).setLineSpacing(3f * dp, 1f).build()
            }
            runs.clear()
        }
        runs.removeAll { now - it.born > REVEAL_MILLIS }
        runs += RevealRun(if (append) value.length else 0, normalized.length, now)
        messageId = id
        value = normalized
        rebuildLayout()
        invalidate()
    }

    private inner class RevealSpan(private val run: RevealRun) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            val fraction = if (motion) ((SystemClock.uptimeMillis() - run.born).toFloat() / REVEAL_MILLIS).coerceIn(0f, 1f) else 1f
            val progress = easing.getInterpolation(fraction)
            tp.alpha = (255 * progress).roundToInt()
            tp.baselineShift = ((1f - progress) * 9f * dp).roundToInt()
            tp.maskFilter = blur[((1f - progress) * 12).roundToInt().coerceIn(0, 12)]
        }
    }

    private fun rebuildLayout() {
        if (layoutWidth <= 0) return
        val styled = SpannableString(value)
        runs.forEach { run ->
            if (run.from < run.to && run.to <= styled.length) styled.setSpan(RevealSpan(run), run.from, run.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val next = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, layoutWidth)
            .setAlignment(if (!value.contains('\n') && paint.measureText(value) <= layoutWidth) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setLineSpacing(3f * dp, 1f).build()
        val visibleLines = next.lineCount.coerceAtMost(4)
        val textHeight = next.getLineBottom(visibleLines - 1)
        val desired = (textHeight + 28f * dp).roundToInt().coerceAtLeast((56f * dp).roundToInt())
        scrollFrom = currentScroll()
        scrollTo = (next.height - textHeight).coerceAtLeast(0).toFloat()
        scrollStartedAt = SystemClock.uptimeMillis()
        layout = next
        if (animatedHeight == 0 || !motion) {
            animatedHeight = desired
            requestLayout()
        } else if (desired != targetHeight) {
            sizeAnimator?.cancel()
            sizeAnimator = ValueAnimator.ofInt(animatedHeight, desired).apply {
                duration = 360
                interpolator = easing
                addUpdateListener {
                    animatedHeight = it.animatedValue as Int
                    requestLayout()
                    onHeightChanged?.invoke()
                }
                start()
            }
        }
        targetHeight = desired
    }

    private var targetHeight = 0
    private var scrollStartedAt = 0L
    private fun currentScroll(): Float {
        val t = ((SystemClock.uptimeMillis() - scrollStartedAt) / 300f).coerceIn(0f, 1f)
        return scrollFrom + (scrollTo - scrollFrom) * easing.getInterpolation(t)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - 36f * dp).roundToInt().coerceAtLeast(1)
        if (available != layoutWidth) { layoutWidth = available; rebuildLayout() }
        setMeasuredDimension(width, animatedHeight.coerceAtLeast(ceil(56f * dp).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val incoming = layout ?: return
        val swap = if (motion) ((now - changedAt) / 440f).coerceIn(0f, 1f) else 1f
        val progress = easing.getInterpolation(swap)
        canvas.save()
        canvas.clipRect(12f * dp, 6f * dp, width - 12f * dp, height - 6f * dp)
        if (progress < 1f) outgoing?.let { old ->
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), ((1f - progress) * 255).toInt())
            canvas.translate(18f * dp, textTop(old) - (old.height - height + 28f * dp).coerceAtLeast(0f) - progress * 10f * dp)
            old.draw(canvas)
            canvas.restore()
        } else outgoing = null
        canvas.translate(18f * dp, textTop(incoming) - currentScroll())
        incoming.draw(canvas)
        canvas.restore()
        if (motion && (progress < 1f || now - scrollStartedAt < 300 || runs.any { now - it.born < REVEAL_MILLIS })) postInvalidateOnAnimation()
    }

    private fun textTop(text: StaticLayout): Float {
        val visibleHeight = text.height.toFloat().coerceAtMost((height - 28f * dp).coerceAtLeast(0f))
        return (height - visibleHeight) / 2f
    }

    override fun onDetachedFromWindow() {
        sizeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    companion object { private const val REVEAL_MILLIS = 420L }
}
