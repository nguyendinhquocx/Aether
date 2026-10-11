package com.zhousl.aether.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.zhousl.aether.platform.LocalReduceMotion
import kotlin.math.min
import kotlin.time.TimeSource

internal const val StreamingSourceTag = "aether-markdown-source"
private const val BatchWindowMillis = 64L
private const val FenceHoldMillis = 240L
internal const val StreamingFadeMillis = 400L
private const val MaximumStaggerMillis = 160L

internal data class StreamingRevealBatch(val range: IntRange, val startedAtMillis: Long) {
    fun alpha(nowMillis: Long): Float {
        val progress = ((nowMillis - startedAtMillis).toFloat() / StreamingFadeMillis).coerceIn(0f, 1f)
        return progress * progress * (3f - 2f * progress)
    }
}

/** Presentation buffering only: never changes the provider stream or persisted response. */
internal class StreamingRevealTimeline(initialText: String = "") {
    var source: String = initialText
        private set
    var visibleText: String = initialText
        private set
    var batches: List<StreamingRevealBatch> = emptyList()
        private set
    private var pendingSince: Long? = null
    private var streaming = true

    fun update(text: String, nowMillis: Long, isStreaming: Boolean = true) {
        if (!text.startsWith(source)) {
            // A retry/replacement must not reuse the previous response's source offsets.
            visibleText = ""
            batches = emptyList()
            pendingSince = null
        }
        source = text
        streaming = isStreaming
        if (visibleText.length < source.length && pendingSince == null) pendingSince = nowMillis
        advance(nowMillis)
    }

    fun advance(nowMillis: Long) {
        batches = batches.filter { it.alpha(nowMillis) < 1f }
        val pendingAt = pendingSince ?: return
        if (streaming && nowMillis - pendingAt < BatchWindowMillis) return
        val units = streamingRevealUnits(source)
        val readyEnd = if (streaming && nowMillis - pendingAt < FenceHoldMillis) {
            units.openFenceStart?.coerceAtLeast(visibleText.length) ?: source.length
        } else source.length
        val ranges = units.ranges.mapNotNull { range ->
            val start = maxOf(visibleText.length, range.first)
            val end = minOf(readyEnd, range.last + 1)
            if (end > start) start until end else null
        }
        if (ranges.isEmpty()) return
        val spacing = if (ranges.size < 2) 0L else min(45L, MaximumStaggerMillis / (ranges.size - 1))
        batches = batches + ranges.mapIndexed { index, range ->
            StreamingRevealBatch(range, nowMillis + index * spacing)
        }
        visibleText = source.substring(0, readyEnd)
        pendingSince = if (readyEnd < source.length) pendingAt else null
    }

    fun alphaAt(sourceOffset: Int, nowMillis: Long): Float =
        batches.firstOrNull { sourceOffset in it.range }?.alpha(nowMillis) ?: 1f

    fun hasWork(): Boolean = pendingSince != null || batches.isNotEmpty()
}

internal data class StreamingRevealUnits(val ranges: List<IntRange>, val openFenceStart: Int?)

/** Short phrases share an opacity; headings and fenced code are atomic presentation units. */
internal fun streamingRevealUnits(text: String): StreamingRevealUnits {
    val ranges = mutableListOf<IntRange>()
    var offset = 0
    var fenceStart: Int? = null
    var fenceMarker = ""
    for (line in text.splitToSequence('\n')) {
        val end = min(text.length, offset + line.length + 1)
        val trimmed = line.trimStart()
        val markerChar = trimmed.firstOrNull()
        val markerLength = if (markerChar == '`' || markerChar == '~') trimmed.takeWhile { it == markerChar }.length else 0
        if (fenceStart != null) {
            if (trimmed.startsWith(fenceMarker) && trimmed.all { it == fenceMarker[0] || it.isWhitespace() }) {
                ranges += fenceStart until end
                fenceStart = null
            }
        } else if (markerLength >= 3) {
            fenceStart = offset
            fenceMarker = trimmed.take(markerLength)
        } else if (trimmed.startsWith('#')) {
            if (end > offset) ranges += offset until end
        } else {
            var start = offset
            var index = offset
            while (index < end) {
                val char = text[index]
                val next = text.getOrNull(index + 1)
                val canSplit = next == null || (!next.isLowSurrogate() &&
                    next.category !in listOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK) &&
                    next != '\u200D' && char != '\u200D')
                val boundary = char in "，。！？；：、,.!?;:\n" ||
                    (index - start >= 27 && (char.isWhitespace() || char.code in 0x2E80..0x9FFF))
                if (canSplit && boundary) {
                    ranges += start..index
                    start = index + 1
                }
                index++
            }
            if (start < end) ranges += start until end
        }
        offset = end
    }
    fenceStart?.let { ranges += it until text.length }
    return StreamingRevealUnits(ranges, fenceStart)
}

@Stable
private class StreamingRevealState(initialText: String) {
    val timeline = StreamingRevealTimeline(initialText)
    var text by mutableStateOf(initialText)
    var frameMillis by mutableLongStateOf(0L)
    var batches by mutableStateOf(emptyList<StreamingRevealBatch>())
    private val epoch = TimeSource.Monotonic.markNow()
    fun nowMillis(): Long = epoch.elapsedNow().inWholeMilliseconds
    fun tick(nowMillis: Long) {
        timeline.advance(nowMillis)
        text = timeline.visibleText
        batches = timeline.batches
        frameMillis = nowMillis
    }
}

private val LocalStreamingReveal = compositionLocalOf<StreamingRevealState?> { null }

// Android replaces its pending lazy-list item with a saved message on completion.
// Retain only recent active presentations, scoped to this conversation, across that handoff.
private class StreamingRevealStore {
    val states = mutableListOf<StreamingRevealState>()
    fun register(state: StreamingRevealState) {
        states += state
        if (states.size > 16) states.removeAt(0)
    }
    fun take(source: String): StreamingRevealState? {
        val state = states.lastOrNull {
            it.timeline.source == source && it.timeline.hasWork() &&
                it.nowMillis() - it.frameMillis < StreamingFadeMillis + MaximumStaggerMillis
        } ?: return null
        states.remove(state)
        return state
    }
}
private val LocalStreamingRevealStore = compositionLocalOf<StreamingRevealStore?> { null }

@Composable
fun StreamingRevealHost(conversationKey: String, content: @Composable () -> Unit) {
    val store = remember(conversationKey) { StreamingRevealStore() }
    CompositionLocalProvider(LocalStreamingRevealStore provides store, content = content)
}

/** Frame ticks are read only by drawing/layer code, not Markdown parsing or text layout. */
@Composable
fun StreamingRevealContent(
    source: String,
    isStreaming: Boolean = true,
    content: @Composable (String) -> Unit,
) {
    if (!isStreaming && LocalStreamingReveal.current != null) {
        content(source)
        return
    }
    val normalized = remember(source) { source.replace("\r\n", "\n") }
    val reduceMotion = LocalReduceMotion.current
    val store = LocalStreamingRevealStore.current
    val state = remember {
        (if (!isStreaming) store?.take(normalized) else null)
            ?: StreamingRevealState(if (isStreaming && !reduceMotion) "" else normalized).also {
                if (isStreaming) store?.register(it)
            }
    }
    LaunchedEffect(normalized, isStreaming, reduceMotion) {
        if (!reduceMotion) {
            val now = state.nowMillis()
            state.timeline.update(normalized, now, isStreaming)
            state.tick(now)
            while (state.timeline.hasWork()) withFrameMillis { state.tick(state.nowMillis()) }
        }
    }
    CompositionLocalProvider(LocalStreamingReveal provides state.takeUnless { reduceMotion }) {
        content(if (reduceMotion) normalized else state.text)
    }
}

/** Source mappings survive removal of Markdown delimiters and link destinations. */
fun AnnotatedString.Builder.appendStreamingSource(text: String, sourceOffset: Int) {
    if (text.isEmpty()) return
    val start = length
    append(text)
    addStringAnnotation(StreamingSourceTag, sourceOffset.toString(), start, length)
}

internal fun streamingDisplayRanges(text: AnnotatedString, sourceRange: IntRange): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    for (annotation in text.getStringAnnotations(StreamingSourceTag, 0, text.length)) {
        val sourceStart = annotation.item.toIntOrNull() ?: continue
        val start = maxOf(sourceStart, sourceRange.first)
        val end = minOf(sourceStart + annotation.end - annotation.start, sourceRange.last + 1)
        if (end <= start) continue
        val displayStart = annotation.start + start - sourceStart
        val displayEnd = annotation.start + end - sourceStart
        if (ranges.lastOrNull()?.last?.plus(1) == displayStart) {
            ranges[ranges.lastIndex] = ranges.last().first until displayEnd
        } else ranges += displayStart until displayEnd
    }
    return ranges
}

@Composable
fun RevealingMarkdownText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onClick: ((Int) -> Unit)? = null,
) {
    val state = LocalStreamingReveal.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val paint = remember { Paint() }
    val maskRanges = remember(text, state?.batches) {
        state?.batches.orEmpty().flatMap { batch ->
            streamingDisplayRanges(text, batch.range).map { batch to it }
        }
    }
    val maskCache = remember(text, maskRanges) { StreamingTextMaskCache() }
    val revealModifier = if (state == null || maskRanges.isEmpty()) modifier else modifier.drawWithContent {
        val now = state.frameMillis
        if (maskRanges.none { it.first.alpha(now) < 1f }) {
            drawContent()
        } else if (layout?.layoutInput?.text == text) {
            val masks = maskCache.paths(requireNotNull(layout), maskRanges)
            // Mask alpha, preserving link colors, bold text, code backgrounds and selection.
            drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), paint) }
            drawContent()
            for ((batch, path) in masks) {
                val alpha = batch.alpha(now)
                if (alpha < 1f) drawPath(path, Color.Black, alpha = 1f - alpha, blendMode = BlendMode.DstOut)
            }
            drawIntoCanvas { it.restore() }
        }
    }
    if (onClick == null) {
        BasicText(text, modifier = revealModifier, style = style, onTextLayout = { layout = it })
    } else {
        @Suppress("DEPRECATION")
        ClickableText(text, modifier = revealModifier, style = style, onTextLayout = { layout = it }, onClick = onClick)
    }
}

private class StreamingTextMaskCache {
    private var layout: TextLayoutResult? = null
    private var masks = emptyList<Pair<StreamingRevealBatch, Path>>()
    fun paths(currentLayout: TextLayoutResult, ranges: List<Pair<StreamingRevealBatch, IntRange>>): List<Pair<StreamingRevealBatch, Path>> {
        if (layout !== currentLayout) {
            layout = currentLayout
            masks = ranges.map { (batch, range) -> batch to currentLayout.getPathForRange(range.first, range.last + 1) }
        }
        return masks
    }
}

/** Apply before backgrounds/padding so a code block's entire surface fades as one unit. */
@Composable
fun Modifier.streamingBlockReveal(sourceOffset: Int): Modifier {
    val state = LocalStreamingReveal.current ?: return this
    return graphicsLayer { alpha = state.timeline.alphaAt(sourceOffset, state.frameMillis) }
}
