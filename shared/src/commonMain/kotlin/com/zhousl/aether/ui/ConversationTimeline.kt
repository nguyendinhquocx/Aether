package com.zhousl.aether.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhousl.aether.platform.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

data class ConversationTimelineEntry(
    val key: String,
    val userPreview: String,
    val assistantPreview: String,
)

private val TimelineMotionEasing = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)
private val TimelineRailWidth = 58.dp
private val TimelineBarHeight = 3.dp
private val TimelineBarGap = 8.dp
private val TimelineVerticalPadding = 10.dp
private val TimelineCompactTouchHeight = 56.dp
private val TimelineMovementThreshold = 18.dp
private val TimelineExitTolerance = 28.dp

@Composable
fun ConversationTimeline(
    entries: List<ConversationTimelineEntry>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit,
) {
    if (entries.size < 2) return

    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val latestOnNavigate by rememberUpdatedState(onNavigate)
    val safeCurrentIndex = currentIndex.coerceIn(entries.indices)
    var navigating by remember(entries) { mutableStateOf(false) }
    var selectedIndex by remember(entries) { mutableIntStateOf(safeCurrentIndex) }
    var dragDistancePx by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var finalPointer by remember { mutableStateOf<Offset?>(null) }
    var railSize by remember { mutableStateOf(IntSize.Zero) }
    var previewHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(safeCurrentIndex, navigating) {
        if (!navigating) selectedIndex = safeCurrentIndex
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val naturalNavigationHeight = TimelineVerticalPadding * 2 +
            TimelineBarHeight * entries.size +
            TimelineBarGap * (entries.size - 1)
        val navigationHeight = naturalNavigationHeight
            .coerceAtLeast(TimelineCompactTouchHeight)
            .coerceAtMost(maxHeight)
        val targetRailHeight = if (navigating) navigationHeight else TimelineCompactTouchHeight
        val animatedRailHeight by animateDpAsState(
            targetValue = targetRailHeight,
            animationSpec = tween(
                durationMillis = if (reduceMotion) 0 else 280,
                easing = TimelineMotionEasing,
            ),
            label = "conversation_timeline_height",
        )
        val expansionFraction by animateFloatAsState(
            targetValue = if (navigating) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (reduceMotion) 0 else 260,
                easing = TimelineMotionEasing,
            ),
            label = "conversation_timeline_expansion",
        )
        val selectionPosition = remember(entries) { Animatable(safeCurrentIndex.toFloat()) }
        val compactWindowPosition = remember(entries) {
            Animatable(compactTimelineWindowStart(entries.size, safeCurrentIndex).toFloat())
        }
        val selectionTarget = if (navigating) selectedIndex else safeCurrentIndex
        LaunchedEffect(selectionTarget, reduceMotion) {
            if (reduceMotion) {
                selectionPosition.snapTo(selectionTarget.toFloat())
            } else {
                selectionPosition.animateTo(
                    targetValue = selectionTarget.toFloat(),
                    animationSpec = tween(durationMillis = 110, easing = TimelineMotionEasing),
                )
            }
        }
        val compactWindowTarget = compactTimelineWindowStart(entries.size, safeCurrentIndex)
        LaunchedEffect(compactWindowTarget, reduceMotion) {
            if (reduceMotion) {
                compactWindowPosition.snapTo(compactWindowTarget.toFloat())
            } else {
                compactWindowPosition.animateTo(
                    targetValue = compactWindowTarget.toFloat(),
                    animationSpec = tween(durationMillis = 190, easing = TimelineMotionEasing),
                )
            }
        }

        val movementThresholdPx = with(density) { TimelineMovementThreshold.toPx() }
        val exitTolerancePx = with(density) { TimelineExitTolerance.toPx() }
        val verticalPaddingPx = with(density) { TimelineVerticalPadding.toPx() }
        val barHeightPx = with(density) { TimelineBarHeight.toPx() }

        Canvas(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(TimelineRailWidth)
                .height(animatedRailHeight)
                .onSizeChanged { railSize = it }
                .pointerInput(entries.size, safeCurrentIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        navigating = true
                        selectedIndex = safeCurrentIndex
                        dragDistancePx = 0f
                        dragOffset = Offset.Zero
                        finalPointer = down.position
                        var pointerInRoot = Offset(
                            x = down.position.x,
                            y = down.position.y + constraints.maxHeight - railSize.height,
                        )

                        val pointerId = down.id
                        var cancelled = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            val nextPointerInRoot = Offset(
                                x = change.position.x,
                                y = change.position.y + constraints.maxHeight - railSize.height,
                            )
                            val dragAmount = nextPointerInRoot - pointerInRoot
                            pointerInRoot = nextPointerInRoot
                            dragOffset += dragAmount
                            dragDistancePx = maxOf(dragDistancePx, dragOffset.getDistance())
                            finalPointer = change.position
                            if (dragDistancePx >= movementThresholdPx && railSize.height > 0) {
                                selectedIndex = timelineIndexForPosition(
                                    positionY = change.position.y,
                                    height = railSize.height.toFloat(),
                                    count = entries.size,
                                    verticalPadding = verticalPaddingPx,
                                    barHeight = barHeightPx,
                                )
                            }
                            pressed = change.pressed
                            change.consume()
                        }

                        val pointer = finalPointer
                        val stayedNearRail = pointer != null &&
                            pointer.x >= -exitTolerancePx &&
                            pointer.x <= railSize.width + exitTolerancePx &&
                            pointer.y >= -exitTolerancePx &&
                            pointer.y <= railSize.height + exitTolerancePx
                        val shouldNavigate = !cancelled &&
                            dragDistancePx >= movementThresholdPx &&
                            stayedNearRail
                        val destination = selectedIndex
                        navigating = false
                        selectedIndex = safeCurrentIndex
                        if (shouldNavigate) latestOnNavigate(destination)
                    }
                },
        ) {
            val compactCount = entries.size.coerceAtMost(4)
            val compactTop = size.height - with(density) {
                (
                    TimelineVerticalPadding +
                        TimelineBarHeight * compactCount +
                        TimelineBarGap * (compactCount - 1)
                    ).toPx()
            }
            val compactStep = barHeightPx + with(density) { TimelineBarGap.toPx() }
            val navigationTop = verticalPaddingPx
            val navigationSpace = (size.height - verticalPaddingPx * 2 - barHeightPx).coerceAtLeast(0f)
            val navigationStep = if (entries.size > 1) {
                navigationSpace / (entries.size - 1)
            } else {
                0f
            }
            val endX = size.width - with(density) { 11.dp.toPx() }

            entries.indices.forEach { index ->
                val collapsedSlot = index - compactWindowPosition.value
                val collapsedY = compactTop + collapsedSlot * compactStep + barHeightPx / 2f
                val expandedY = navigationTop + index * navigationStep + barHeightPx / 2f
                val y = lerp(
                    Offset(0f, collapsedY),
                    Offset(0f, expandedY),
                    expansionFraction,
                ).y
                val distance = abs(index - selectionPosition.value)
                val compactWidth = compactTimelineBarWidth(distance)
                val navigationWidth = navigationTimelineBarWidth(distance)
                val widthPx = with(density) {
                    (compactWidth + (navigationWidth - compactWidth) * expansionFraction).dp.toPx()
                }
                val compactColor = compactTimelineBarColor(distance)
                val navigationColor = navigationTimelineBarColor(distance, isDarkTheme)
                val color = lerp(compactColor, navigationColor, expansionFraction)
                val collapsedAlpha = compactTimelineSlotAlpha(collapsedSlot, compactCount)
                val alpha = collapsedAlpha + (1f - collapsedAlpha) * expansionFraction

                drawLine(
                    color = color.copy(alpha = color.alpha * alpha),
                    start = Offset(endX - widthPx, y),
                    end = Offset(endX, y),
                    strokeWidth = barHeightPx,
                    cap = StrokeCap.Round,
                )
            }
        }

        val previewEntry = entries[selectedIndex.coerceIn(entries.indices)]
        val navigationHeightPx = with(density) { navigationHeight.toPx() }
        val rootHeightPx = constraints.maxHeight.toFloat()
        val selectedCenterInRail = timelinePositionForIndex(
            index = selectedIndex,
            height = navigationHeightPx,
            count = entries.size,
            verticalPadding = verticalPaddingPx,
            barHeight = barHeightPx,
        )
        val selectedCenterInRoot = rootHeightPx - navigationHeightPx + selectedCenterInRail
        val previewTopPx = (selectedCenterInRoot - previewHeightPx / 2f)
            .coerceIn(0f, (rootHeightPx - previewHeightPx).coerceAtLeast(0f))
        val previewBackground = if (isDarkTheme) Color(0xFF2C2C2E) else Color(0xFFF0F0F2)
        val previewUserColor = if (isDarkTheme) Color.White else Color(0xFF1C1C1E)
        val previewAssistantColor = if (isDarkTheme) Color(0xFF9A9A9E) else Color(0xFF68686C)

        AnimatedVisibility(
            visible = navigating,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = TimelineRailWidth + 4.dp)
                .offset(y = with(density) { previewTopPx.toDp() }),
            enter = fadeIn(tween(if (reduceMotion) 0 else 150)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(if (reduceMotion) 0 else 190, easing = TimelineMotionEasing),
            ),
            exit = fadeOut(tween(if (reduceMotion) 0 else 110)) + scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(if (reduceMotion) 0 else 130, easing = TimelineMotionEasing),
            ),
        ) {
            Column(
                modifier = Modifier
                    .onSizeChanged { previewHeightPx = it.height }
                    .widthIn(min = 180.dp, max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(previewBackground.copy(alpha = 0.98f))
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text(
                    text = previewEntry.userPreview.ifBlank { " " },
                    color = previewUserColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (previewEntry.assistantPreview.isNotBlank()) {
                    Text(
                        text = previewEntry.assistantPreview,
                        color = previewAssistantColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
    }
}

internal fun compactTimelineIndices(total: Int, currentIndex: Int): List<Int> {
    if (total <= 0) return emptyList()
    val visibleCount = total.coerceAtMost(4)
    val start = compactTimelineWindowStart(total, currentIndex)
    return (start until start + visibleCount).toList()
}

internal fun compactTimelineWindowStart(total: Int, currentIndex: Int): Int {
    if (total <= 0) return 0
    val visibleCount = total.coerceAtMost(4)
    val safeCurrent = currentIndex.coerceIn(0, total - 1)
    return (safeCurrent - 1).coerceIn(0, total - visibleCount)
}

private fun compactTimelineSlotAlpha(slot: Float, visibleCount: Int): Float {
    if (visibleCount <= 0) return 0f
    val lastSlot = visibleCount - 1f
    return when {
        slot < -1f || slot > lastSlot + 1f -> 0f
        slot < 0f -> slot + 1f
        slot <= lastSlot -> 1f
        else -> lastSlot + 1f - slot
    }.coerceIn(0f, 1f)
}

internal fun timelineIndexForPosition(
    positionY: Float,
    height: Float,
    count: Int,
    verticalPadding: Float,
    barHeight: Float,
): Int {
    if (count <= 1) return 0
    val usableHeight = (height - verticalPadding * 2 - barHeight).coerceAtLeast(1f)
    val normalized = ((positionY - verticalPadding - barHeight / 2f) / usableHeight)
        .coerceIn(0f, 1f)
    return (normalized * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

private fun timelinePositionForIndex(
    index: Int,
    height: Float,
    count: Int,
    verticalPadding: Float,
    barHeight: Float,
): Float {
    if (count <= 1) return height / 2f
    val usableHeight = (height - verticalPadding * 2 - barHeight).coerceAtLeast(0f)
    return verticalPadding + barHeight / 2f + usableHeight * index.coerceIn(0, count - 1) / (count - 1)
}

private fun compactTimelineBarWidth(distance: Float): Float = when {
    distance < 1f -> 31f - 8f * distance
    distance < 2f -> 23f - 5f * (distance - 1f)
    else -> (18f - 3f * (distance - 2f)).coerceAtLeast(12f)
}

private fun navigationTimelineBarWidth(distance: Float): Float = when {
    distance < 1f -> 36f - 11f * distance
    distance < 2f -> 25f - 9f * (distance - 1f)
    else -> 16f
}

private fun compactTimelineBarColor(distance: Float): Color {
    if (distance < 0.5f) return Color(0xFF777777)
    val alpha = when (floor(distance).toInt()) {
        0, 1 -> 0.52f
        2 -> 0.36f
        else -> 0.24f
    }
    return Color(0xFFB8B8B8).copy(alpha = alpha)
}

private fun navigationTimelineBarColor(distance: Float, isDarkTheme: Boolean): Color {
    val selectedAmount = (1f - distance).coerceIn(0f, 1f)
    val inactive = if (isDarkTheme) Color(0xFF464646) else Color(0xFFC5C5C7)
    val selected = if (isDarkTheme) Color.White else Color(0xFF1C1C1E)
    return lerp(inactive, selected, selectedAmount)
}
