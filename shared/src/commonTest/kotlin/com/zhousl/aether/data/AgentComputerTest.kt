package com.zhousl.aether.data

import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.FileSystem

class AgentComputerTest {
    @Test fun decodesSplitUnicodeAndAnsiWithoutDuplicatingProgress() {
        val terminal = AgentTerminalTranscript()
        val bytes = "你好 😀\n\u001b[32m10%\u001b[0m\r100%\u001b[K".encodeToByteArray()
        bytes.forEach { terminal.append(byteArrayOf(it)) }
        terminal.finish()
        assertEquals("你好 😀\n100%", terminal.text())
    }
    @Test fun clearAndCursorMovementAreLimitedToTheCurrentExecution() {
        val terminal = AgentTerminalTranscript()
        terminal.append("old\u001b[2J\u001b[Hnew\nsecond\u001b[1A\u001b[1GOK\u001b[K".encodeToByteArray())
        assertEquals("OK\nsecond", terminal.text())
    }
    @Test fun boundsScrollbackAndConsumesOscControls() {
        val terminal = AgentTerminalTranscript()
        terminal.append(("\u001b]0;secret title\u0007" + "line\n".repeat(3000)).encodeToByteArray())
        assertTrue(terminal.trimmed)
        assertFalse(terminal.text().contains("secret"))
        assertTrue(terminal.text().length < 20000)
    }
    @Test fun concurrentExecutionsKeepOwnershipSequenceAndHistory() = runTest {
        val store = AgentComputerRepository()
        suspend fun send(event: String, id: String, text: String = "", sequence: Int = 0, session: String = "one") = store.accept(event, buildJsonObject {
            put("session_id", session); put("execution_id", id); put("command", "echo $id"); put("cwd", "/root")
            put("started_at", 1); put("sequence", sequence); put("bytes_base64", Base64.encode(text.encodeToByteArray()))
            put("completed_at", 3); put("exit_code", 7)
        })
        send("process_start", "a"); send("process_start", "b")
        send("process_chunk", "a", "first", 0); send("process_chunk", "b", "second", 0)
        send("process_chunk", "a", "duplicate", 0)
        send("process_end", "a"); send("process_chunk", "a", "late", 1)
        send("process_start", "c", session = "two")
        val executions = store.sessions.value.getValue("one")
        assertEquals(listOf("a", "b"), executions.map { it.id })
        assertEquals(listOf("first", "second"), executions.map { it.output })
        assertEquals(7, executions[0].exitCode)
        assertNotNull(executions[0].completedAt)
        assertNull(executions[1].completedAt)
        assertEquals(listOf("c"), store.sessions.value.getValue("two").map { it.id })
    }
    @Test fun restoresHistoryWithoutReexecutingAndDeletesOnlyItsOwnLog() = runTest {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "aether-observer-${platformRandomUuid()}"
        try {
            val first = AgentComputerRepository().apply { this.directory = directory.toString() }
            first.accept("process_start", buildJsonObject {
                put("session_id", "../../outside"); put("execution_id", "command"); put("command", "never run me"); put("cwd", "/root")
            })
            val restored = AgentComputerRepository().apply { this.directory = directory.toString() }
            restored.restore("../../outside")
            assertEquals("interrupted", restored.sessions.value.getValue("../../outside").single().error)
            assertEquals(1, FileSystem.SYSTEM.list(directory).size)
            restored.remove("../../outside")
            assertTrue(FileSystem.SYSTEM.list(directory).isEmpty())
        } finally { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }
    }
}
