package com.zhousl.aether.data

import com.zhousl.aether.data.pi.PiKernelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val PiThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

internal fun supportedThinkingLevels(levels: JSONArray): List<String> =
    buildList {
        for (index in 0 until levels.length()) {
            val level = levels.optString(index).trim()
            if (level in PiThinkingLevels && level !in this) add(level)
        }
    }

internal fun piThinkingLevelClamps(clamps: JSONObject): Map<String, String> =
    PiThinkingLevels.mapNotNull { level ->
        clamps.optString(level).takeIf { it in PiThinkingLevels }?.let { level to it }
    }.toMap()

internal fun thinkingCatalogKey(providerId: String, modelId: String): String =
    "${providerId.trim()}/${modelId.substringAfterLast('/').trim()}"

private fun publicCatalogProviderId(providerId: String): String = when (providerId.trim()) {
    "openai-codex" -> "openai"
    else -> providerId.trim()
}

private fun publicCatalogModelKeys(providerId: String, modelId: String): List<String> {
    val normalized = modelId.substringAfterLast('/').trim()
    return buildList {
        add(normalized)
        if (providerId == "kimi-coding" && normalized.startsWith("kimi-")) {
            add(normalized.removePrefix("kimi-"))
        }
        if (providerId == "kimi-coding" && normalized == "k3") add("kimi-k3")
    }.distinct()
}

object ProviderModelCatalogClient {

    data class FetchModelsResult(
        val models: List<String>,
        val error: String? = null,
        val thinkingLevelsByModel: Map<String, List<String>> = emptyMap(),
        val thinkingLevelClampsByModel: Map<String, Map<String, String>> = emptyMap(),
    )

    suspend fun fetchModels(
        config: LlmProviderConfig,
        piKernelBridge: PiKernelBridge? = null,
        startPiBridgeIfNeeded: Boolean = true,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        try {
            val definition = PiProviderCatalog.resolve(
                config.piProviderId,
            )
            if (!shouldFetchModelsFromEndpoint(config, definition)) {
                return@withContext fetchPiBuiltinModels(
                    definition = definition,
                    piKernelBridge = piKernelBridge,
                    startPiBridgeIfNeeded = startPiBridgeIfNeeded,
                )
            }
            fetchOpenAiModels(config)
        } catch (e: Exception) {
            FetchModelsResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    suspend fun fetchPiThinkingLevels(
        config: LlmProviderConfig,
        piKernelBridge: PiKernelBridge?,
        startPiBridgeIfNeeded: Boolean = true,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        try {
            val definition = PiProviderCatalog.resolve(config.piProviderId)
            if (!definition.isBuiltIn) return@withContext FetchModelsResult(emptyList())
            fetchPiBuiltinModels(
                definition = definition,
                piKernelBridge = piKernelBridge,
                startPiBridgeIfNeeded = startPiBridgeIfNeeded,
            )
        } catch (e: Exception) {
            FetchModelsResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    suspend fun fetchPublicThinkingLevels(options: List<ProviderModelOption>): Map<String, List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("https://models.dev/catalog.json").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                try {
                    if (connection.responseCode != 200) return@runCatching emptyMap()
                    val providers = JSONObject(connection.inputStream.bufferedReader().readText())
                        .optJSONObject("providers") ?: return@runCatching emptyMap()
                    buildMap {
                        options.forEach { option ->
                            val provider = providers.optJSONObject(publicCatalogProviderId(option.piProviderId))
                                ?: return@forEach
                            val models = provider.optJSONObject("models") ?: return@forEach
                            val model = publicCatalogModelKeys(option.piProviderId, option.modelId)
                                .firstNotNullOfOrNull { key -> models.optJSONObject(key) }
                                ?: models.keys().asSequence().mapNotNull { key ->
                                    models.optJSONObject(key)?.takeIf { candidate ->
                                        publicCatalogModelKeys(option.piProviderId, candidate.optString("id"))
                                            .any { it.equals(option.modelId.substringAfterLast('/').trim(), ignoreCase = true) }
                                    }
                                }.firstOrNull()
                            val levels = model?.takeIf { it.optBoolean("reasoning") }?.let { definition ->
                                buildList {
                                    val reasoningOptions = definition.optJSONArray("reasoning_options")
                                    if ((0 until (reasoningOptions?.length() ?: 0)).any {
                                            reasoningOptions?.optJSONObject(it)?.optString("type") == "toggle"
                                        }) add("off")
                                    for (index in 0 until (reasoningOptions?.length() ?: 0)) {
                                        val effort = reasoningOptions?.optJSONObject(index) ?: continue
                                        if (effort.optString("type") != "effort") continue
                                        val values = effort.optJSONArray("values") ?: continue
                                        for (valueIndex in 0 until values.length()) {
                                            val value = values.optString(valueIndex).trim()
                                            val normalizedValue = if (value == "none") "off" else value
                                            if (normalizedValue in PiThinkingLevels && normalizedValue !in this) add(normalizedValue)
                                        }
                                    }
                                }
                            }.orEmpty()
                            put(thinkingCatalogKey(option.piProviderId, option.modelId), levels)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(emptyMap())
        }

    private suspend fun fetchPiBuiltinModels(
        definition: PiProviderDefinition,
        piKernelBridge: PiKernelBridge?,
        startPiBridgeIfNeeded: Boolean,
    ): FetchModelsResult {
        if (piKernelBridge == null) {
            return FetchModelsResult(listOf(definition.defaultModelId).filter(String::isNotBlank))
        }
        val providers = piKernelBridge.listProviders(startIfNeeded = startPiBridgeIfNeeded)
            .optJSONArray("providers")
            ?: return FetchModelsResult(emptyList(), "No provider catalog was returned.")
        for (providerIndex in 0 until providers.length()) {
            val provider = providers.optJSONObject(providerIndex) ?: continue
            if (provider.optString("id") != definition.id) continue
            val models = provider.optJSONArray("models") ?: return FetchModelsResult(emptyList())
            val thinkingLevelsByModel = mutableMapOf<String, List<String>>()
            val thinkingLevelClampsByModel = mutableMapOf<String, Map<String, String>>()
            for (modelIndex in 0 until models.length()) {
                val model = models.optJSONObject(modelIndex) ?: continue
                val modelId = model.optString("id").trim()
                if (modelId.isBlank() || !model.optBoolean("reasoning")) continue
                val levels = model.optJSONArray("thinking_levels") ?: JSONArray()
                thinkingLevelsByModel[modelId] = supportedThinkingLevels(levels)
                model.optJSONObject("thinking_level_clamps")?.let { clamps ->
                    thinkingLevelClampsByModel[modelId] = piThinkingLevelClamps(clamps)
                }
            }
            return FetchModelsResult(
                models = buildList {
                    for (modelIndex in 0 until models.length()) {
                        val modelId = models.optJSONObject(modelIndex)
                            ?.optString("id")
                            ?.trim()
                            .orEmpty()
                        if (modelId.isNotBlank()) add(modelId)
                    }
                }.distinct(),
                thinkingLevelsByModel = thinkingLevelsByModel,
                thinkingLevelClampsByModel = thinkingLevelClampsByModel,
            )
        }
        return FetchModelsResult(emptyList(), "Provider ${definition.id} is unavailable.")
    }

    internal fun shouldFetchModelsFromEndpoint(
        config: LlmProviderConfig,
        definition: PiProviderDefinition = PiProviderCatalog.resolve(config.piProviderId),
    ): Boolean {
        if (!definition.isBuiltIn) return true
        if (definition.id == "openai" && config.authMethod == ProviderAuthMethod.ApiKey) return true

        val normalizedBaseUrl = config.baseUrl.trim().trimEnd('/')
        return definition.id == "openai" &&
            normalizedBaseUrl.isNotBlank() &&
            normalizedBaseUrl != definition.defaultBaseUrl
    }

    private fun fetchOpenAiModels(config: LlmProviderConfig): FetchModelsResult {
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val modelsUrl = when {
            baseUrl.endsWith("/responses") -> baseUrl.replace("/responses", "/models")
            baseUrl.endsWith("/chat/completions") -> baseUrl.replace("/chat/completions", "/models")
            baseUrl.endsWith("/v1") -> "$baseUrl/models"
            else -> "$baseUrl/models"
        }

        val connection = URL(modelsUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.applyAetherLlmHeaders(config.userAgent, config.customHeaders)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        return try {
            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)
                val dataArray = json.optJSONArray("data")
                val models = mutableListOf<String>()
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val modelObj = dataArray.optJSONObject(i)
                        val modelId = modelObj?.optString("id")
                        if (!modelId.isNullOrBlank()) {
                            models.add(modelId)
                        }
                    }
                }
                FetchModelsResult(
                    models
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinctBy { it.lowercase() },
                )
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP ${connection.responseCode}"
                FetchModelsResult(emptyList(), errorText)
            }
        } finally {
            connection.disconnect()
        }
    }

}
