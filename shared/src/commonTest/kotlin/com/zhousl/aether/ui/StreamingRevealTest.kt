package com.zhousl.aether.ui

import androidx.compose.ui.text.buildAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamingRevealTest {
    @Test
    fun tinyNetworkUpdatesAreCoalescedWithoutRestartingOlderFades() {
        val timeline = StreamingRevealTimeline()
        timeline.update("第", 0)
        timeline.update("第一段", 20)
        timeline.update("第一段。", 45)
        assertEquals("", timeline.visibleText)
        timeline.advance(64)
        assertEquals("第一段。", timeline.visibleText)
        assertEquals(1, timeline.batches.size)
        val oldAlpha = timeline.alphaAt(0, 164)
        timeline.update("第一段。第二段。", 164)
        timeline.advance(228)
        assertEquals(2, timeline.batches.size)
        assertTrue(timeline.alphaAt(0, 228) > oldAlpha)
        assertTrue(timeline.alphaAt(0, 228) < 1f)
        assertEquals(0f, timeline.alphaAt(4, 228))
        timeline.advance(628)
        assertFalse(timeline.hasWork())
        assertEquals("第一段。第二段。", timeline.visibleText)
    }

    @Test
    fun finishingFlushesBufferedTextAndLetsExistingFadesComplete() {
        val timeline = StreamingRevealTimeline()
        timeline.update("Hello", 0)
        timeline.advance(64)
        timeline.update("Hello world", 100, isStreaming = false)
        assertEquals("Hello world", timeline.visibleText)
        assertTrue(timeline.alphaAt(0, 100) < 1f)
        assertEquals(0f, timeline.alphaAt(7, 100))
        timeline.advance(500)
        assertFalse(timeline.hasWork())
    }

    @Test
    fun shortCodeFenceWaitsForClosureThenFadesAsOneSurface() {
        val timeline = StreamingRevealTimeline()
        timeline.update("Before.\n\n```python\ndef greet", 0)
        timeline.advance(64)
        assertEquals("Before.\n\n", timeline.visibleText)
        val full = "Before.\n\n```python\ndef greet():\n    return 1\n```\nAfter."
        timeline.update(full, 160)
        assertEquals(full, timeline.visibleText)
        val code = timeline.batches.first { full.indexOf("def greet") in it.range }
        assertTrue(full.indexOf("return 1") in code.range)
        assertEquals(0f, code.alpha(160))
    }

    @Test
    fun slowOrUnclosedFenceCannotHideOutputIndefinitely() {
        val timeline = StreamingRevealTimeline()
        val source = "~~~kotlin\nval value = 1"
        timeline.update(source, 0)
        timeline.advance(239)
        assertEquals("", timeline.visibleText)
        timeline.advance(240)
        assertEquals(source, timeline.visibleText)
        timeline.update("$source\nval next = 2", 260)
        timeline.advance(500)
        assertEquals("$source\nval next = 2", timeline.visibleText)
    }

    @Test
    fun largeBurstHasBoundedPresentationDelayAndPreservesAllText() {
        val source = (1..200).joinToString("\n") { "第${it}行，完整内容。" }
        val timeline = StreamingRevealTimeline()
        timeline.update(source, 0)
        timeline.advance(64)
        assertEquals(source, timeline.visibleText)
        assertTrue(timeline.batches.maxOf { it.startedAtMillis } <= 224)
        timeline.advance(624)
        assertFalse(timeline.hasWork())
    }

    @Test
    fun replacementAndDeletionDiscardOldSourceOffsets() {
        val timeline = StreamingRevealTimeline()
        timeline.update("old response", 0)
        timeline.advance(64)
        timeline.update("新的回答", 100)
        assertEquals("", timeline.visibleText)
        timeline.advance(164)
        assertEquals("新的回答", timeline.visibleText)
        assertEquals(0 until 4, timeline.batches.single().range)
        timeline.update("", 200)
        assertEquals("", timeline.visibleText)
        assertFalse(timeline.hasWork())
    }

    @Test
    fun historyStartsVisibleAndDoesNotReplay() {
        val timeline = StreamingRevealTimeline("Saved answer")
        timeline.update("Saved answer", 0, isStreaming = false)
        assertEquals("Saved answer", timeline.visibleText)
        assertFalse(timeline.hasWork())
    }

    @Test
    fun mappingsFollowVisibleLabelsAcrossRemovedMarkdownSyntax() {
        val rendered = buildAnnotatedString {
            appendStreamingSource("bold", 2) // **bold**
            appendStreamingSource(" link", 10) // label inside a link
        }
        assertEquals(listOf(2..6), streamingDisplayRanges(rendered, 4..12))
        assertEquals(emptyList(), streamingDisplayRanges(rendered, 6..9))
        assertEquals("bold link", rendered.text)
    }

    @Test
    fun batchingPreservesUnicodeAndWholeHeadings() {
        val source = "# 一级标题 (H1)\n你好👨‍👩‍👧‍👦，Cafe\u0301 与𠀀字符。\n~~~js\nx()\n~~~"
        val units = streamingRevealUnits(source)
        assertEquals(source, units.ranges.joinToString("") { source.substring(it) })
        assertEquals("# 一级标题 (H1)\n", source.substring(units.ranges.first()))
        assertTrue(units.ranges.none { source[it.last].isHighSurrogate() })
        assertEquals(null, units.openFenceStart)
        assertEquals("~~~js\nx()\n~~~", source.substring(units.ranges.last()))
    }
}
