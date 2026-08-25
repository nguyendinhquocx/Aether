package com.zhousl.aether.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class AetherSettingsStoreTest {
    @Test
    fun generalSettingsSurviveSaveAndReload() = runTest {
        val store = AetherSettingsStore(InMemoryPreferencesDataStore())
        val expected = AppSettings(
            language = AppLanguage.SimplifiedChinese,
            themeMode = AppThemeMode.Dark,
            systemPrompt = "Keep these instructions",
            reasoningEffort = "high",
            tavilyApiKey = "tavily-secret",
            tavilyBaseUrl = "https://search.example/",
            keepTasksRunningInBackground = false,
            notifyOnTaskCompletion = false,
            defaultSelectedSkillIds = listOf("review", "research"),
        )

        store.saveGeneralSettings(expected)

        assertEquals(expected, store.load().appSettings)
    }

    @Test
    fun dedicatedKeysOverrideStaleSettingsJson() = runTest {
        val stale = AppSettings(
            language = AppLanguage.English,
            themeMode = AppThemeMode.Light,
            systemPrompt = "stale prompt",
            reasoningEffort = "low",
        )
        val dataStore = InMemoryPreferencesDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("app_settings_json") to serializeAppSettings(stale),
                stringPreferencesKey("language") to AppLanguage.SimplifiedChinese.storageValue,
                stringPreferencesKey("theme_mode") to AppThemeMode.Dark.storageValue,
                stringPreferencesKey("system_prompt") to "current prompt",
                stringPreferencesKey("reasoning_effort") to "high",
            ),
        )

        val loaded = AetherSettingsStore(dataStore).load().appSettings

        assertEquals(AppLanguage.SimplifiedChinese, loaded.language)
        assertEquals(AppThemeMode.Dark, loaded.themeMode)
        assertEquals("current prompt", loaded.systemPrompt)
        assertEquals("high", loaded.reasoningEffort)
    }
}

private class InMemoryPreferencesDataStore(
    initial: Preferences = mutablePreferencesOf(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
