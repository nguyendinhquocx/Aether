package com.zhousl.aether.data

import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath

@Serializable
data class AgentExecution(
    val id: String,
    val command: String,
    val cwd: String,
    val startedAt: Long,
    val sequence: Int = -1,
    val output: String = "",
    val completedAt: Long? = null,
    val exitCode: Int? = null,
    val error: String = "",
    val trimmed: Boolean = false,
)

/** Session-owned state, independent of the lifetime of any observer view. */
data class AgentContextUsage(val used: Long?, val full: Int?)

object AgentComputerStore {
    private val contextState = MutableStateFlow<Map<String, AgentContextUsage>>(emptyMap())
    val contexts = contextState.asStateFlow()
    private val repository = AgentComputerRepository()
    val sessions = repository.sessions
    fun configure(directory: String) { repository.directory = directory }
    suspend fun restore(sessionId: String) = repository.restore(sessionId)
    suspend fun remove(sessionId: String) {
        contextState.value = contextState.value - sessionId
        repository.remove(sessionId)
    }
    suspend fun acceptJson(event: String, payload: String) {
        val json = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
        accept(event, json)
    }
    suspend fun accept(event: String, payload: JsonObject) {
        if (event == "context_usage") {
            val sessionId = payload.text("session_id")
            if (sessionId.isNotBlank()) contextState.value = contextState.value + (sessionId to AgentContextUsage(payload.long("used"), payload.int("full")))
        } else repository.accept(event, payload)
    }
}

internal class AgentComputerRepository {
    var directory: String? = null
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<String, List<AgentExecution>>>(emptyMap())
    val sessions = state.asStateFlow()
    private val terminals = mutableMapOf<String, AgentTerminalTranscript>()
    private val restored = mutableSetOf<String>()
    private val lastSaved = mutableMapOf<String, Long>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun restore(sessionId: String) = withContext(Dispatchers.Default) {
        mutex.withLock { restoreLocked(sessionId) }
    }

    suspend fun remove(sessionId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            state.value[sessionId].orEmpty().forEach { terminals.remove(it.id) }
            state.value = state.value - sessionId
            restored.remove(sessionId)
            lastSaved.remove(sessionId)
            path(sessionId)?.let { file -> runCatching { FileSystem.SYSTEM.delete(file, mustExist = false) } }
        }
    }

    private fun path(sessionId: String) = directory?.let {
        // Never use an externally supplied session id as a filesystem path.
        val key = sessionId.encodeUtf8().sha256().hex()
        "$it/$key.json".toPath()
    }

    private fun restoreLocked(sessionId: String) {
        if (!restored.add(sessionId)) return
        val file = path(sessionId) ?: return
        val entries = runCatching {
            if (!FileSystem.SYSTEM.exists(file)) return
            FileSystem.SYSTEM.read(file) { json.decodeFromString<List<AgentExecution>>(readUtf8()) }
        }.getOrNull().orEmpty().map {
            // A process cannot be reattached after an application restart.
            if (it.completedAt == null) it.copy(completedAt = platformCurrentTimeMillis(), error = "interrupted") else it
        }
        if (entries.isNotEmpty()) state.value = state.value + (sessionId to entries)
    }

    suspend fun accept(event: String, payload: JsonObject) = withContext(Dispatchers.Default) {
        val sessionId = payload.text("session_id")
        val id = payload.text("execution_id")
        if (sessionId.isBlank() || id.isBlank()) return@withContext
        mutex.withLock {
            restoreLocked(sessionId)
            val entries = state.value[sessionId].orEmpty().toMutableList()
            val index = entries.indexOfFirst { it.id == id }
            when (event) {
                "process_start" -> {
                    if (index >= 0) return@withLock
                    terminals[id] = AgentTerminalTranscript()
                    entries += AgentExecution(id, payload.text("command"), payload.text("cwd"), payload.long("started_at") ?: platformCurrentTimeMillis())
                }
                "process_chunk" -> {
                    if (index < 0 || entries[index].completedAt != null) return@withLock
                    val sequence = payload.int("sequence") ?: return@withLock
                    if (sequence <= entries[index].sequence) return@withLock
                    val bytes = runCatching { Base64.decode(payload.text("bytes_base64")) }.getOrNull() ?: return@withLock
                    val terminal = terminals.getOrPut(id) { AgentTerminalTranscript(entries[index].output) }
                    terminal.append(bytes)
                    entries[index] = entries[index].copy(sequence = sequence, output = terminal.text(), trimmed = entries[index].trimmed || terminal.trimmed || sequence > entries[index].sequence + 1)
                }
                "process_end" -> {
                    if (index < 0 || entries[index].completedAt != null) return@withLock
                    terminals.remove(id)?.let { terminal ->
                        terminal.finish()
                        entries[index] = entries[index].copy(output = terminal.text(), trimmed = terminal.trimmed)
                    }
                    entries[index] = entries[index].copy(completedAt = payload.long("completed_at") ?: platformCurrentTimeMillis(), exitCode = payload.int("exit_code"), error = payload.text("error"))
                }
                else -> return@withLock
            }
            // Bounded scrollback; active commands are never evicted by a concurrent command.
            var retained = entries
            while (retained.size > 100 || retained.sumOf { it.output.length + it.command.length } > 2 * 1024 * 1024) {
                val completed = retained.indexOfFirst { it.completedAt != null }
                if (completed < 0) break
                terminals.remove(retained[completed].id)
                retained.removeAt(completed)
                if (retained.isNotEmpty()) retained[0] = retained[0].copy(trimmed = true)
            }
            state.value = state.value + (sessionId to retained.toList())
            val now = platformCurrentTimeMillis()
            if (event != "process_chunk" || now - (lastSaved[sessionId] ?: 0) >= 250) {
                path(sessionId)?.let { file ->
                    runCatching {
                        FileSystem.SYSTEM.createDirectories(file.parent!!)
                        val temporary = (file.toString() + ".tmp").toPath()
                        FileSystem.SYSTEM.write(temporary) { writeUtf8(json.encodeToString(retained.toList())) }
                        FileSystem.SYSTEM.atomicMove(temporary, file)
                        lastSaved[sessionId] = now
                    }
                }
            }
        }
    }
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

/** Incremental UTF-8 and terminal control interpretation, isolated to one command.
 * Screen erasure can never remove a previous command's scrollback. No input/signal API exists.
 */
internal class AgentTerminalTranscript(initial: String = "") {
    private val rows = initial.split('\n').map { StringBuilder(it) }.toMutableList()
    private var row = rows.lastIndex
    private var column = rows.last().length
    private var pending = byteArrayOf()
    private var escape = ""
    private var mode = 0 // normal / ESC / CSI / OSC / OSC ESC
    var trimmed = false
        private set

    fun append(bytes: ByteArray) {
        val all = pending + bytes
        var end = all.size
        // Keep an incomplete UTF-8 scalar for the following chunk.
        var lead = end - 1
        while (lead >= 0 && (all[lead].toInt() and 0xc0) == 0x80) lead--
        if (lead >= 0) {
            val b = all[lead].toInt() and 0xff
            val count = when { b >= 0xf0 -> 4; b >= 0xe0 -> 3; b >= 0xc0 -> 2; else -> 1 }
            if (end - lead < count) end = lead
        }
        pending = all.copyOfRange(end, all.size)
        consume(all.copyOfRange(0, end).decodeToString())
    }
    fun finish() { if (pending.isNotEmpty()) consume(pending.decodeToString()); pending = byteArrayOf() }
    fun text() = rows.joinToString("\n")

    private fun consume(text: String) {
        for (ch in text) {
            when (mode) {
                1 -> { mode = when (ch) { '[' -> 2; ']' -> 3; else -> 0 }; escape = "" }
                2 -> if (ch in '@'..'~') { control(ch); mode = 0; escape = "" } else {
                    escape += ch
                    if (escape.length > 128) { mode = 0; escape = "" }
                }
                3 -> if (ch == '\u0007') mode = 0 else if (ch == '\u001b') mode = 4
                4 -> { mode = if (ch == '\\') 0 else 3 }
                else -> when (ch) {
                    '\u001b' -> mode = 1
                    '\r' -> column = 0
                    '\n' -> { row++; column = 0; ensureRow() }
                    '\b' -> column = (column - 1).coerceAtLeast(0)
                    '\t' -> { repeat(8 - column % 8) { put(' ') } }
                    else -> if (ch >= ' ') put(ch)
                }
            }
        }
        while (rows.size > 2000 || (rows.size > 1 && rows.sumOf { it.length } > 128 * 1024)) {
            rows.removeAt(0); row = (row - 1).coerceAtLeast(0); trimmed = true
        }
    }
    private fun ensureRow() { while (rows.size <= row) rows.add(StringBuilder()) }
    private fun put(ch: Char) {
        ensureRow()
        if (column >= 8192) { trimmed = true; return }
        val line = rows[row]
        while (line.length < column) line.append(' ')
        if (column == line.length) line.append(ch) else line[column] = ch
        column++
    }
    private fun control(final: Char) {
        val args = escape.split(';').map { it.toIntOrNull() ?: 0 }
        val n = (args.firstOrNull() ?: 0).coerceAtLeast(1)
        when (final) {
            'A' -> row = (row - n).coerceAtLeast(0)
            'B' -> { row = (row + n).coerceAtMost(1999); ensureRow() }
            'C' -> column = (column + n).coerceAtMost(8191)
            'D' -> column = (column - n).coerceAtLeast(0)
            'G' -> column = (n - 1).coerceAtMost(8191)
            'H', 'f' -> { row = (n - 1).coerceAtMost(1999); column = ((args.getOrNull(1) ?: 1).coerceAtLeast(1) - 1).coerceAtMost(8191); ensureRow() }
            'J' -> if (args.firstOrNull() in listOf(2, 3)) { rows.clear(); rows.add(StringBuilder()); row = 0; column = 0 } else {
                ensureRow(); rows[row].setLength(column.coerceAtMost(rows[row].length)); while (rows.lastIndex > row) rows.removeAt(rows.lastIndex)
            }
            'K' -> { ensureRow(); when (args.firstOrNull()) {
                2 -> rows[row].clear()
                1 -> for (i in 0 until column.coerceAtMost(rows[row].lastIndex + 1)) rows[row][i] = ' '
                else -> rows[row].setLength(column.coerceAtMost(rows[row].length))
            } }
        }
    }
}
