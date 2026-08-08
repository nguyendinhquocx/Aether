package com.zhousl.aether.data

import com.zhousl.aether.runtime.LocalRuntime
import com.zhousl.aether.runtime.RuntimeRouter
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val RuntimeSkillsRoot = "/data/data/com.termux/files/home/.aether/skills"
private const val SkillMirrorChunkBytes = 48 * 1024

class SkillRuntimeMirror(
    private val runtimeRouter: RuntimeRouter,
) {
    private val mutex = Mutex()
    private val runtimeSignatures = mutableMapOf<LocalRuntimeId, String>()

    suspend fun sync(skills: List<InstalledSkill>): List<String> = mutex.withLock {
        val enabled = skills.filter(InstalledSkill::isEnabled).sortedBy(InstalledSkill::id)
        val signature = enabled.joinToString("|") { "${it.id}:${it.checksumSha256}:${it.updatedAtMillis}" }
        val successful = mutableSetOf<LocalRuntimeId>()
        LocalRuntimeId.entries.forEach { runtimeId ->
            if (runtimeSignatures[runtimeId] == signature) {
                successful += runtimeId
                return@forEach
            }
            val runtime = runtimeRouter.runtimeById(runtimeId)
            if (!runtime.inspectSetup().isReady) return@forEach
            runCatching { syncRuntime(runtime, enabled) }
                .onSuccess {
                    runtimeSignatures[runtimeId] = signature
                    successful += runtimeId
                }
        }
        if (LocalRuntimeId.Alpine !in successful) emptyList() else enabled.map(::runtimeSkillPath)
    }

    private suspend fun syncRuntime(runtime: LocalRuntime, skills: List<InstalledSkill>) {
        runCommand(
            runtime,
            "mkdir -p -- ${shellQuote(RuntimeSkillsRoot)} && find ${shellQuote(RuntimeSkillsRoot)} -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +",
        )
        skills.forEach { skill ->
            val sourceRoot = File(skill.skillRootPath).canonicalFile
            require(sourceRoot.isDirectory) { "Skill root is unavailable: ${skill.name}" }
            val targetRoot = runtimeSkillPath(skill)
            runCommand(runtime, "mkdir -p -- ${shellQuote(targetRoot)}")
            sourceRoot.walkTopDown().forEach { source ->
                if (source == sourceRoot || source.isSymbolicLink()) return@forEach
                val relative = source.relativeTo(sourceRoot).invariantSeparatorsPath
                val target = "$targetRoot/$relative"
                if (source.isDirectory) {
                    runCommand(runtime, "mkdir -p -- ${shellQuote(target)}")
                } else if (source.isFile) {
                    writeFile(runtime, source, target)
                }
            }
        }
    }

    private suspend fun writeFile(runtime: LocalRuntime, source: File, target: String) {
        val temporary = "$target.aether-tmp-${source.length()}-${source.lastModified()}"
        runCommand(
            runtime,
            "mkdir -p -- ${shellQuote(target.substringBeforeLast('/'))} && : > ${shellQuote(temporary)}",
        )
        try {
            source.inputStream().use { input ->
                val buffer = ByteArray(SkillMirrorChunkBytes)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val encoded = Base64.getEncoder().encodeToString(buffer.copyOf(count))
                    runCommand(
                        runtime,
                        "printf '%s' ${shellQuote(encoded)} | base64 -d >> ${shellQuote(temporary)}",
                    )
                }
            }
            runCommand(runtime, "mv -f -- ${shellQuote(temporary)} ${shellQuote(target)}")
        } catch (failure: Throwable) {
            runCatching { runCommand(runtime, "rm -f -- ${shellQuote(temporary)}") }
            throw failure
        }
    }

    private suspend fun runCommand(runtime: LocalRuntime, command: String) {
        val result = JSONObject(
            runtime.executeCommand(
                command = command,
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 60_000L,
            )
        )
        check(result.optBoolean("ok")) {
            result.optString("errmsg").ifBlank {
                result.optString("stderr").ifBlank { "Skill runtime mirror command failed." }
            }
        }
    }
}

fun runtimeSkillPath(skill: InstalledSkill): String = "$RuntimeSkillsRoot/${safeSkillDirectoryName(skill.id)}"

private fun safeSkillDirectoryName(id: String): String {
    val normalized = id.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
    if (normalized.isNotBlank()) return normalized
    return MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}

private fun File.isSymbolicLink(): Boolean = runCatching {
    canonicalFile != absoluteFile
}.getOrDefault(true)

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
