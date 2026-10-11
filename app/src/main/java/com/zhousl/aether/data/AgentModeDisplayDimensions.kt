package com.zhousl.aether.data

/** Physical display size for a newly created managed display; no coordinate-space rotation. */
internal fun agentModeDisplayDimensions(
    width: Int,
    height: Int,
    orientation: AgentModeDisplayOrientation,
): Pair<Int, Int> {
    require(width > 0 && height > 0)
    return when (orientation) {
        AgentModeDisplayOrientation.Device -> width to height
        AgentModeDisplayOrientation.Portrait -> minOf(width, height) to maxOf(width, height)
        AgentModeDisplayOrientation.Landscape -> maxOf(width, height) to minOf(width, height)
    }
}
