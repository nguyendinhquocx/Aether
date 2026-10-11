package com.zhousl.aether.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RootSetupIssue {
    Unknown,
    Available,
    Running,
    Ready,
    Unavailable,
    PermissionDenied,
    TermuxNotInstalled,
    Failed,
}

data class RootSetupState(
    val issue: RootSetupIssue = RootSetupIssue.Unknown,
    val detail: String = "",
    val rootAvailable: Boolean = false,
    val suPath: String = "",
    val didLaunchTermuxForBackground: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
) {
    val isReady: Boolean
        get() = issue == RootSetupIssue.Ready

    val isRunning: Boolean
        get() = issue == RootSetupIssue.Running
}

class RootSetupController(
    private val context: Context,
) {
    suspend fun inspect(): RootSetupState = withContext(Dispatchers.IO) {
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            RootSetupState(
                issue = RootSetupIssue.Unavailable,
                detail = "No su binary was detected on this device.",
                rootAvailable = false,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        } else {
            RootSetupState(
                issue = RootSetupIssue.Available,
                detail = "Root is available for Virtual Display authorization.",
                rootAvailable = true,
                suPath = suPath,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun findSuPath(): String {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")
            .firstOrNull { File(it).canExecute() }?.let { return it }
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v su").start()
            if (!process.waitFor(1_500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                ""
            } else process.inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
    }
}
