package com.zhousl.aether.platform

import com.zhousl.aether.data.AgentExecution
import com.zhousl.aether.data.pi.SharedBrowserDisplayState
import kotlinx.serialization.json.*
import kotlin.test.*

class IosComputerSnapshotTest {
    @Test
    fun nativeTransportKeepsHistoryAndRejectsAnotherSessionsBrowser() {
        val commands = listOf(
            AgentExecution("one", "echo first", "/workspace", 10, output = "first\n", completedAt = 11, exitCode = 0),
            AgentExecution("two", "echo second", "/workspace", 20, output = "second"),
        )
        val browser = SharedBrowserDisplayState(
            sessionId = "other", isActive = true, screenshotBase64 = "frame", url = "https://example.com", lastUpdatedMillis = 30,
        )
        val payload = Json.parseToJsonElement(iosComputerSnapshot("current", "/workspace", true, commands, browser)).jsonObject
        assertEquals(2, payload.getValue("executions").jsonArray.size)
        assertEquals("first\n", payload.getValue("executions").jsonArray[0].jsonObject.getValue("output").jsonPrimitive.content)
        assertFalse(payload.getValue("browserActive").jsonPrimitive.boolean)
        assertFalse(payload.getValue("browserReady").jsonPrimitive.boolean)
        assertEquals("", payload.getValue("browserURL").jsonPrimitive.content)
        val own = Json.parseToJsonElement(iosComputerSnapshot("other", "/workspace", true, commands, browser)).jsonObject
        assertTrue(own.getValue("browserReady").jsonPrimitive.boolean)
        assertTrue(own.getValue("browserActive").jsonPrimitive.boolean)
    }
}
