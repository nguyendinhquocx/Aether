package com.zhousl.aether.data.pi

import com.zhousl.aether.data.platformCurrentTimeMillis

// Kept only so older archives and persisted session models remain decodable.
enum class SharedMcpTransport { Http, Stdio }

data class SharedMcpServerConfig(
    val id: String = "mcp-${platformCurrentTimeMillis()}",
    val name: String,
    val actionLabel: String = "",
    val transport: SharedMcpTransport,
    val url: String = "",
    val command: String = "",
    val arguments: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val workingDirectory: String = "",
    val environment: Map<String, String> = emptyMap(),
    val runtimeEnvironment: String = "default",
    val connectTimeoutMillis: Long = 15_000L,
    val requestTimeoutMillis: Long = 60_000L,
    val enabled: Boolean = true,
    val createdAtMillis: Long = platformCurrentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
)
