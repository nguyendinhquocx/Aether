package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelCatalogClientTest {
    @Test
    fun thinkingCatalogKeyUsesOnlyLastModelIdSegment() {
        assertEquals(
            "openrouter/gpt-5.6-sol",
            thinkingCatalogKey("openrouter", "openai/gpt-5.6-sol"),
        )
    }

    @Test
    fun openAiCompatibleKimiK3UsesModelsDevMoonshotCatalog() {
        val config = LlmProviderConfig(
            providerId = "modal",
            name = "Modal",
            piProviderId = "openai-compatible",
            apiKey = "test-key",
            baseUrl = "https://example.modal.run/v1",
            modelId = "kimi-k3",
            cachedModels = listOf("kimi-k3"),
            enabledModelIds = listOf("kimi-k3"),
        )
        val option = listOf(config).availableModelOptions().single()
        val catalog = JSONObject(
            """{"providers":{"moonshotai":{"models":{"kimi-k3":{"id":"kimi-k3","reasoning":true,"reasoning_options":[{"type":"toggle"},{"type":"effort","values":["low","high","max"]}]}}}}}""",
        )

        assertEquals(
            listOf("off", "low", "high", "max"),
            publicCatalogThinkingLevels(catalog, listOf(option))[
                thinkingCatalogKey("openai-compatible", "kimi-k3")
            ],
        )
    }

    @Test
    fun fetchModelsIncludesConfiguredUserAgentAndCustomHeaders() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"gpt-test"}]}""")
        )
        server.start()

        try {
            val result = ProviderModelCatalogClient.fetchModels(
                LlmProviderConfig(
                    providerId = "openai",
                    name = "OpenAI",
                    piProviderId = "openai-compatible",
                    apiKey = "test-key",
                    baseUrl = server.url("/v1").toString(),
                    modelId = "gpt-test",
                    userAgent = "CatalogClient/1.0",
                    customHeaders = listOf(
                        LlmCustomHeader("X-Aether-Test", "models"),
                        LlmCustomHeader("User-Agent", "ignored"),
                    ),
                )
            )

            assertEquals(listOf("gpt-test"), result.models)
            val request = server.takeRequest()
            assertEquals("CatalogClient/1.0", request.getHeader("User-Agent"))
            assertEquals("models", request.getHeader("X-Aether-Test"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun builtInProviderModelsAreParsedFromModelsDev() {
        val result = modelsDevProviderModels(
            JSONObject(
                """{"providers":{"google-vertex":{"models":{"gemini-new":{"id":"gemini-new"},"fallback-key":{}}}}}""",
            ),
            listOf("google-vertex"),
        )

        assertEquals(null, result.error)
        assertEquals(listOf("gemini-new", "fallback-key"), result.models)
    }

    @Test
    fun modelsDevProviderAliasesMatchAetherBuiltIns() {
        assertEquals(
            listOf("fireworks-ai"),
            PiProviderCatalog.resolve("fireworks").modelsDevProviderIds(),
        )
        assertEquals(
            listOf("kimi-for-coding"),
            PiProviderCatalog.resolve("kimi-coding").modelsDevProviderIds(),
        )
    }

    @Test
    fun builtInOpenAiApiKeyUsesLiveModelsEndpoint() {
        val definition = PiProviderCatalog.resolve("openai")
        val config = LlmProviderConfig(
            providerId = "openai",
            name = "OpenAI",
            piProviderId = "openai",
            apiKey = "test-key",
            baseUrl = definition.defaultBaseUrl,
            modelId = "",
            authMethod = ProviderAuthMethod.ApiKey,
        )

        assertEquals(
            true,
            ProviderModelCatalogClient.shouldFetchModelsFromEndpoint(config, definition),
        )
    }

    @Test
    fun openAiCodexOAuthKeepsProviderCatalog() {
        val definition = PiProviderCatalog.resolve("openai-codex")
        val config = LlmProviderConfig(
            providerId = "openai-codex",
            name = "OpenAI Codex",
            piProviderId = "openai-codex",
            apiKey = "",
            baseUrl = definition.defaultBaseUrl,
            modelId = "",
            authMethod = ProviderAuthMethod.OAuth,
        )

        assertEquals(
            false,
            ProviderModelCatalogClient.shouldFetchModelsFromEndpoint(config, definition),
        )
    }

    @Test
    fun customOpenAiBaseUrlFetchesModelsFromConfiguredEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"third-party-model"}]}""")
        )
        server.start()

        try {
            val result = ProviderModelCatalogClient.fetchModels(
                LlmProviderConfig(
                    providerId = "custom-openai",
                    name = "Custom OpenAI",
                    piProviderId = "openai",
                    apiKey = "test-key",
                    baseUrl = server.url("/v1").toString(),
                    modelId = "",
                )
            )

            assertEquals(null, result.error)
            assertEquals(listOf("third-party-model"), result.models)
            assertEquals("/v1/models", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun failedLiveRequestDoesNotReturnBundledModels() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"invalid key"}}""")
        )
        server.start()

        try {
            val result = ProviderModelCatalogClient.fetchModels(
                LlmProviderConfig(
                    providerId = "openai",
                    name = "OpenAI",
                    piProviderId = "openai",
                    apiKey = "invalid-key",
                    baseUrl = server.url("/v1").toString(),
                    modelId = "",
                    authMethod = ProviderAuthMethod.ApiKey,
                )
            )

            assertEquals(emptyList<String>(), result.models)
            assertEquals("""{"error":{"message":"invalid key"}}""", result.error)
        } finally {
            server.shutdown()
        }
    }
}
