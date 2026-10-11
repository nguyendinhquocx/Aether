package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentModeDisplayDimensionsTest {
    @Test fun deviceKeepsOriginalDimensions() {
        assertEquals(1220 to 2712, agentModeDisplayDimensions(1220, 2712, AgentModeDisplayOrientation.Device))
        assertEquals(2712 to 1220, agentModeDisplayDimensions(2712, 1220, AgentModeDisplayOrientation.Device))
    }

    @Test fun landscapeSwapsPortraitDimensionsAndPreservesLandscape() {
        assertEquals(2712 to 1220, agentModeDisplayDimensions(1220, 2712, AgentModeDisplayOrientation.Landscape))
        assertEquals(2712 to 1220, agentModeDisplayDimensions(2712, 1220, AgentModeDisplayOrientation.Landscape))
    }

    @Test fun portraitSwapsLandscapeDimensionsAndPreservesPortrait() {
        assertEquals(1220 to 2712, agentModeDisplayDimensions(2712, 1220, AgentModeDisplayOrientation.Portrait))
        assertEquals(1220 to 2712, agentModeDisplayDimensions(1220, 2712, AgentModeDisplayOrientation.Portrait))
    }

    @Test fun squareDisplayIsUnchanged() {
        AgentModeDisplayOrientation.entries.forEach {
            assertEquals(1000 to 1000, agentModeDisplayDimensions(1000, 1000, it))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDimensions() {
        agentModeDisplayDimensions(0, 2712, AgentModeDisplayOrientation.Landscape)
    }

    @Test fun normalizedCoordinatesUseLandscapeExtent() {
        val (width, height) = agentModeDisplayDimensions(1220, 2712, AgentModeDisplayOrientation.Landscape)
        assertEquals(AgentModeCoordinateResult.Valid(1356), resolveAgentModeCoordinate("x", 500.0, width))
        assertEquals(AgentModeCoordinateResult.Valid(610), resolveAgentModeCoordinate("y", 500.0, height))
        assertEquals(1280 to 575, agentModeScreenshotSize(width, height, 1280))
    }
}
