package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ConversionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "syniji_settings")

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configKey = stringPreferencesKey("default_conversion_config")

    val configFlow: Flow<ConversionConfig> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[configKey]
        if (raw.isNullOrBlank()) {
            ConversionConfig()
        } else {
            try {
                json.decodeFromString<ConversionConfig>(raw)
            } catch (_: Exception) {
                ConversionConfig()
            }
        }
    }

    suspend fun saveConfig(config: ConversionConfig) {
        val encoded = json.encodeToString(config)
        context.settingsDataStore.edit { prefs ->
            prefs[configKey] = encoded
        }
    }
}
