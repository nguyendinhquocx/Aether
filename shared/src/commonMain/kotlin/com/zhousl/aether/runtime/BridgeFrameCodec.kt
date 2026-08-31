package com.zhousl.aether.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BridgeFrameCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val onMalformedLine: (line: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private var pending = ByteArray(0)

    fun append(bytes: ByteArray): List<JsonObject> {
        if (bytes.isEmpty()) return emptyList()
        pending += bytes
        val frames = mutableListOf<JsonObject>()
        var start = 0
        pending.indices.forEach { index ->
            if (pending[index] != '\n'.code.toByte()) return@forEach
            val line = pending.copyOfRange(start, index).decodeToString().trim()
            if (line.isNotEmpty()) {
                try {
                    frames += json.parseToJsonElement(line).jsonObject
                } catch (error: Exception) {
                    onMalformedLine(line, error)
                }
            }
            start = index + 1
        }
        pending = pending.copyOfRange(start, pending.size)
        return frames
    }

    fun encode(frame: JsonObject): ByteArray =
        (json.encodeToString(JsonObject.serializer(), frame) + "\n").encodeToByteArray()
}
