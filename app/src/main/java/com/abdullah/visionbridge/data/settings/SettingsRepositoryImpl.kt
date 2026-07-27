package com.abdullah.visionbridge.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "vision_bridge_settings")

class SettingsRepositoryImpl(private val context: Context) : SettingsRepository {
    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { values ->
        val storedModel = values[Keys.MODEL]
        AppSettings(
            mode = AnalysisMode.fromStored(values[Keys.MODE]),
            model = storedModel?.takeIf { it in AppSettings.SUPPORTED_MODELS } ?: AppSettings.DEFAULT_MODEL,
            forceCellular = values[Keys.FORCE_CELLULAR] ?: false,
            speechEnabled = values[Keys.SPEECH] ?: true,
            localOcrEnabled = values[Keys.LOCAL_OCR] ?: true,
        )
    }

    override suspend fun setMode(mode: AnalysisMode) = update(Keys.MODE, mode.name)
    override suspend fun setModel(model: String) {
        require(model in AppSettings.SUPPORTED_MODELS) { "نموذج غير مدعوم" }
        update(Keys.MODEL, model)
    }
    override suspend fun setForceCellular(enabled: Boolean) = update(Keys.FORCE_CELLULAR, enabled)
    override suspend fun setSpeechEnabled(enabled: Boolean) = update(Keys.SPEECH, enabled)
    override suspend fun setLocalOcrEnabled(enabled: Boolean) = update(Keys.LOCAL_OCR, enabled)

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val MODEL = stringPreferencesKey("model")
        val FORCE_CELLULAR = booleanPreferencesKey("force_cellular")
        val SPEECH = booleanPreferencesKey("speech")
        val LOCAL_OCR = booleanPreferencesKey("local_ocr")
    }
}
