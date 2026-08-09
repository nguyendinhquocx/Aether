package com.zhousl.aether.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val ThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

internal fun thinkingCatalogKey(providerId: String, modelId: String): String =
    "${providerId.trim()}/${modelId.substringAfterLast('/').trim()}"

private fun ProviderModelOption.publicCatalogProviderIds(): List<String> = buildList {
    add(
        when (piProviderId.trim()) {
            "openai-codex" -> "openai"
            "kimi-coding" -> "moonshotai"
            else -> piProviderId.trim()
        }
    )
    modelId.substringBeforeLast('/', "").trim().takeIf(String::isNotBlank)?.let(::add)
    if (modelId.substringAfterLast('/').trim().startsWith("kimi-", ignoreCase = true)) {
        add("moonshotai")
    }
}.filter(String::isNotBlank).distinct()

private fun ProviderModelOption.publicCatalogModelKeys(): List<String> = listOf(
    modelId,
    modelId.substringAfter("$piProviderId/", modelId),
    modelId.substringAfterLast('/'),
).map(String::trim).filter(String::isNotBlank).distinct()

private fun JSONObject.findPublicCatalogModel(option: ProviderModelOption): JSONObject? {
    option.publicCatalogModelKeys().firstNotNullOfOrNull(::optJSONObject)?.let { return it }
    val normalizedModelId = option.modelId.substringAfterLast('/').trim()
    return keys().asSequence().mapNotNull { key ->
        optJSONObject(key)?.takeIf { model ->
            listOf(key, model.optString("id")).any { candidateId ->
                candidateId.substringAfterLast('/').trim()
                    .equals(normalizedModelId, ignoreCase = true)
            }
        }
    }.firstOrNull()
}

private fun JSONObject.findPublicCatalogModelAcrossProviders(option: ProviderModelOption): JSONObject? {
    option.publicCatalogProviderIds().firstNotNullOfOrNull { providerId ->
        optJSONObject(providerId)?.optJSONObject("models")?.findPublicCatalogModel(option)
    }?.let { return it }
    return keys().asSequence().mapNotNull { providerId ->
        optJSONObject(providerId)?.optJSONObject("models")?.findPublicCatalogModel(option)
    }.firstOrNull()
}

internal fun publicCatalogThinkingLevels(
    catalog: JSONObject,
    options: List<ProviderModelOption>,
): Map<String, List<String>> {
    val providers = catalog.optJSONObject("providers") ?: return emptyMap()
    return buildMap {
        options.forEach { option ->
            val model = providers.findPublicCatalogModelAcrossProviders(option)
            val levels = model?.takeIf { it.optBoolean("reasoning") }?.let { definition ->
                buildList {
                    val reasoningOptions = definition.optJSONArray("reasoning_options")
                    if ((0 until (reasoningOptions?.length() ?: 0)).any {
                            reasoningOptions?.optJSONObject(it)?.optString("type") == "toggle"
                        }
                    ) {
                        add("off")
                    }
                    for (index in 0 until (reasoningOptions?.length() ?: 0)) {
                        val effort = reasoningOptions?.optJSONObject(index) ?: continue
                        if (effort.optString("type") != "effort") continue
                        val values = effort.optJSONArray("values") ?: continue
                        for (valueIndex in 0 until values.length()) {
                            val value = values.optString(valueIndex).trim()
                            val normalizedValue = if (value == "none") "off" else value
                            if (normalizedValue in ThinkingLevels && normalizedValue !in this) {
                                add(normalizedValue)
                            }
                        }
                    }
                }
            }.orEmpty()
            put(thinkingCatalogKey(option.piProviderId, option.modelId), levels)
        }
    }
}

object ProviderModelCatalogClient {

    data class FetchModelsResult(
        val models: List<String>,
        val error: String? = null,
    )

    suspend fun fetchModels(
        config: LlmProviderConfig,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        try {
            val definition = PiProviderCatalog.resolve(config.piProviderId)
            if (shouldFetchModelsFromEndpoint(config, definition)) {
                val endpoint = fetchOpenAiModels(config)
                val publicCatalog = if (shouldMergeModelsDev(config, definition)) {
                    fetchModelsDevModels(definition)
                } else {
                    FetchModelsResult(emptyList())
                }
                val merged = (endpoint.models + publicCatalog.models)
                    .distinctBy(String::lowercase)
                return@withContext FetchModelsResult(
                    models = merged,
                    error = if (merged.isEmpty()) endpoint.error ?: publicCatalog.error else null,
                )
            }
            fetchModelsDevModels(definition)
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
                    publicCatalogThinkingLevels(
                        JSONObject(connection.inputStream.bufferedReader().readText()),
                        options,
                    )
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(emptyMap())
        }

    private fun fetchModelsDevModels(
        definition: PiProviderDefinition,
    ): FetchModelsResult {
        val connection = URL("https://models.dev/catalog.json").openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        return try {
            if (connection.responseCode != 200) {
                return FetchModelsResult(emptyList(), "models.dev returned HTTP ${connection.responseCode}.")
            }
            modelsDevProviderModels(
                JSONObject(connection.inputStream.bufferedReader().readText()),
                definition.modelsDevProviderIds(),
            )
        } finally {
            connection.disconnect()
        }
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

    private fun shouldMergeModelsDev(
        config: LlmProviderConfig,
        definition: PiProviderDefinition,
    ): Boolean = definition.isBuiltIn &&
        config.baseUrl.trim().trimEnd('/') == definition.defaultBaseUrl.trim().trimEnd('/')

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

internal fun modelsDevProviderModels(
    catalog: JSONObject,
    providerIds: List<String>,
): ProviderModelCatalogClient.FetchModelsResult {
    val providers = catalog.optJSONObject("providers")
        ?: return ProviderModelCatalogClient.FetchModelsResult(
            emptyList(),
            "No provider catalog was returned by models.dev.",
        )
    providerIds.forEach { providerId ->
        val models = providers.optJSONObject(providerId)?.optJSONObject("models") ?: return@forEach
        val modelIds = buildList {
            models.keys().forEach { key ->
                val modelId = models.optJSONObject(key)?.optString("id").orEmpty()
                    .ifBlank { key }
                    .trim()
                if (modelId.isNotBlank()) add(modelId)
            }
        }.distinctBy(String::lowercase)
        if (modelIds.isNotEmpty()) {
            return ProviderModelCatalogClient.FetchModelsResult(modelIds)
        }
    }
    return ProviderModelCatalogClient.FetchModelsResult(
        emptyList(),
        "Provider ${providerIds.joinToString()} is unavailable in models.dev.",
    )
}
