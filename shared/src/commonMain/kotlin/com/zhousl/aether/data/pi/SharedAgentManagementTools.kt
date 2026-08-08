package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppLanguage
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.AppThemeMode
import com.zhousl.aether.data.SharedInstalledSkill
import com.zhousl.aether.data.SharedSkillManager
import com.zhousl.aether.data.normalizeLlmInactivityReconnectTimeoutSeconds
import com.zhousl.aether.data.normalizeOldCommandHistoryRetentionHours
import com.zhousl.aether.runtime.MultiplatformLocalRuntime
import com.zhousl.aether.runtime.SharedPiBridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Aether-owned host tools shared by Android and iOS. */
class SharedAgentManagementTools(
    private val runtime: MultiplatformLocalRuntime,
    private val bridge: SharedPiBridgeClient,
    private val skillManager: SharedSkillManager,
    private val settings: suspend () -> AppSettings,
    private val updateSettings: suspend (AppSettings) -> Unit,
    private val currentSessionId: suspend () -> String,
) : SharedSessionAwareHostToolExecutor {
    override val definitions: JsonArray = buildJsonArray {
        addSelfManagementDefinitions()
    }

    override fun definitions(sessionId: String): JsonArray = definitions

    override suspend fun execute(name: String, arguments: JsonObject): SharedHostToolResult = try {
        when (name) {
            "aether_config_get" -> getConfiguration(arguments)
            "aether_config_set" -> setConfiguration(arguments)
            "aether_skill_manage" -> manageSkills(arguments)
            "aether_extension_manage" -> manageExtensions(arguments)
            "aether_developer_manage" -> manageDeveloper(arguments)
            else -> error("Unsupported Aether host tool: $name")
        }
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (failure: Throwable) {
        agentToolFailure(failure)
    }

    private suspend fun getConfiguration(arguments: JsonObject): SharedHostToolResult {
        val requested = (arguments["categories"] as? JsonArray).orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .map(String::lowercase)
            .toSet()
        val categories = requested.ifEmpty {
            setOf("general", "reliability", "agent_skills", "developer")
        }
        val current = settings()
        return agentToolSuccess("Read ${categories.size} Aether configuration categories.") {
            categories.forEach { category ->
                when (category) {
                    "general" -> put("general", buildJsonObject {
                        put("language", current.language.storageValue)
                        put("theme_mode", current.themeMode.storageValue)
                    })
                    "reliability" -> put("reliability", buildJsonObject {
                        put(
                            "llm_inactivity_reconnect_timeout_seconds",
                            current.llmInactivityReconnectTimeoutSeconds,
                        )
                        put("auto_clean_old_command_history", current.autoCleanOldCommandHistory)
                        put("old_command_history_retention_hours", current.oldCommandHistoryRetentionHours)
                    })
                    "agent_skills" -> put("agent_skills", skillsJson(skillManager.list()))
                    "developer" -> put("developer", buildJsonObject {
                        put("runtime_home", runtime.homeDirectory)
                        put("workspace", runtime.workspaceRoot)
                        put("tools", JsonArray(listOf(JsonPrimitive("aether_developer_manage"))))
                    })
                    else -> error("Unsupported category '$category'.")
                }
            }
        }
    }

    private suspend fun setConfiguration(arguments: JsonObject): SharedHostToolResult {
        val category = arguments.string("category").trim().lowercase()
        val patch = arguments["settings"] as? JsonObject ?: error("settings must be an object.")
        val current = settings()
        val updated = when (category) {
            "general" -> current.copy(
                language = patch.stringOrNull("language")
                    ?.let { AppLanguage.fromStorage(it, current.language) }
                    ?: current.language,
                themeMode = patch.stringOrNull("theme_mode")
                    ?.let(AppThemeMode::fromStorage)
                    ?: current.themeMode,
            )
            "reliability" -> current.copy(
                llmInactivityReconnectTimeoutSeconds =
                    patch.int("llm_inactivity_reconnect_timeout_seconds")
                        ?.let(::normalizeLlmInactivityReconnectTimeoutSeconds)
                        ?: current.llmInactivityReconnectTimeoutSeconds,
                autoCleanOldCommandHistory = patch.booleanOrNull("auto_clean_old_command_history")
                    ?: current.autoCleanOldCommandHistory,
                oldCommandHistoryRetentionHours = patch.int("old_command_history_retention_hours")
                    ?.let(::normalizeOldCommandHistoryRetentionHours)
                    ?: current.oldCommandHistoryRetentionHours,
            )
            else -> error("Unsupported settings category '$category'.")
        }
        updateSettings(updated)
        return agentToolSuccess("Updated Aether $category settings.") { put("category", category) }
    }

    private suspend fun manageSkills(arguments: JsonObject): SharedHostToolResult {
        val action = arguments.string("action").lowercase()
        when (action) {
            "list" -> Unit
            "install_remote" -> {
                val url = arguments.string("url")
                require(url.isNotBlank()) { "url is required for install_remote." }
                skillManager.installRemote(url)
            }
            "remove" -> {
                val skillId = arguments.string("skill_id")
                require(skillId.isNotBlank()) { "skill_id is required for remove." }
                skillManager.remove(skillId)
            }
            "set_enabled" -> {
                val skillId = arguments.string("skill_id")
                val enabled = arguments.booleanOrNull("enabled")
                require(skillId.isNotBlank() && enabled != null) {
                    "skill_id and enabled are required for set_enabled."
                }
                skillManager.setEnabled(skillId, enabled)
            }
            else -> error("Unsupported Skill action '$action'.")
        }
        if (action != "list") bridge.reloadSession(currentSessionId())
        val skills = skillManager.list()
        return agentToolSuccess("${action.ifBlank { "Listed" }} ${skills.size} Agent Skill(s).") {
            put("skills", skillsJson(skills))
        }
    }

    private suspend fun manageExtensions(arguments: JsonObject): SharedHostToolResult {
        val action = arguments.string("action").lowercase()
        val sessionId = currentSessionId()
        val response = when (action) {
            "list" -> bridge.listExtensions(sessionId)
            "reload" -> bridge.reloadExtensions(sessionId)
            "invoke_command" -> {
                val command = arguments.string("command")
                require(command.isNotBlank()) { "command is required for invoke_command." }
                bridge.invokeExtensionCommand(sessionId, command, arguments.string("args"))
            }
            else -> error("Unsupported Pi extension action '$action'.")
        }
        return agentToolSuccess("Completed Pi extension action '$action'.") {
            response.forEach { (key, value) -> put(key, value) }
        }
    }

    private suspend fun manageDeveloper(arguments: JsonObject): SharedHostToolResult {
        require(arguments.string("action").ifBlank { "read_diagnostics" } == "read_diagnostics") {
            "Unsupported developer action."
        }
        val ping = bridge.ping()
        return agentToolSuccess("Read Aether runtime diagnostics.") {
            put("runtime", ping)
            put("runtime_home", runtime.homeDirectory)
            put("workspace", runtime.workspaceRoot)
            put("events_tail", "")
            put("last_crash", "")
        }
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addSelfManagementDefinitions() {
    add(agentToolDefinition(
        "aether_config_get",
        "Read Aether general, reliability, Agent Skills, and developer configuration.",
        "parallel",
        properties = mapOf("categories" to agentStringArraySchema("Optional categories to read.")),
    ))
    add(agentToolDefinition(
        "aether_config_set",
        "Modify allowed Aether general or reliability settings.",
        "sequential",
        listOf("category", "settings"),
        mapOf(
            "category" to agentStringSchema("One of general, reliability."),
            "settings" to buildJsonObject {
                put("type", "object")
                put("additionalProperties", true)
            },
        ),
    ))
    add(agentToolDefinition(
        "aether_skill_manage",
        "List, remotely install, enable, disable, or remove Aether Agent Skills.",
        "sequential",
        listOf("action"),
        mapOf(
            "action" to agentStringSchema("One of list, install_remote, remove, set_enabled."),
            "skill_id" to agentStringSchema("Installed Skill id."),
            "url" to agentStringSchema("HTTPS GitHub or zip URL."),
            "enabled" to agentBooleanSchema("Whether the Skill is enabled."),
        ),
    ))
    add(agentToolDefinition(
        "aether_extension_manage",
        "List or reload Pi extensions for the current session, or invoke a registered command.",
        "sequential",
        listOf("action"),
        mapOf(
            "action" to agentStringSchema("One of list, reload, invoke_command."),
            "command" to agentStringSchema("Registered extension command."),
            "args" to agentStringSchema("Raw command argument string."),
        ),
    ))
    add(agentToolDefinition(
        "aether_developer_manage",
        "Read non-sensitive Aether runtime diagnostics.",
        "parallel",
        listOf("action"),
        mapOf(
            "action" to agentStringSchema("read_diagnostics"),
            "include" to agentStringSchema("events, last_crash, or both."),
            "max_chars" to agentIntegerSchema("Maximum diagnostic characters."),
        ),
    ))
}

private fun agentToolDefinition(
    name: String,
    description: String,
    executionMode: String,
    required: List<String> = emptyList(),
    properties: Map<String, JsonObject> = emptyMap(),
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put("execution_mode", executionMode)
    put("parameters", buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    })
}

private fun agentStringSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun agentIntegerSchema(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun agentBooleanSchema(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun agentStringArraySchema(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

private inline fun agentToolSuccess(
    stdout: String,
    content: JsonObjectBuilder.() -> Unit = {},
): SharedHostToolResult = SharedHostToolResult(
    buildJsonObject {
        put("ok", true)
        put("stdout", stdout)
        content()
    }.toString(),
)

private fun agentToolFailure(error: Throwable): SharedHostToolResult = SharedHostToolResult(
    buildJsonObject {
        put("ok", false)
        put("errmsg", error.message ?: "Aether host tool failed.")
    }.toString(),
    isError = true,
)

private fun skillsJson(skills: List<SharedInstalledSkill>): JsonArray = buildJsonArray {
    skills.sortedBy { it.name.lowercase() }.forEach { skill ->
        add(buildJsonObject {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("is_enabled", skill.isEnabled)
            put("compatibility", skill.compatibility)
            put("license", skill.license)
            put("source", skill.source)
            put("allowed_tools", JsonArray(skill.allowedTools.map(::JsonPrimitive)))
            put("resource_count", skill.resourceCount)
        })
    }
}

private fun JsonObject.string(name: String): String =
    get(name)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    get(name)?.jsonPrimitive?.booleanOrNull
