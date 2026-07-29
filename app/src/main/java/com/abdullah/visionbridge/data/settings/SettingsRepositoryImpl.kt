package com.abdullah.visionbridge.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
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
            // Versioned keys intentionally stop older aggressive defaults from surviving an upgrade.
            // Users can still re-enable either option explicitly from the accessible settings screen.
            trustGateEnabled = values[Keys.TRUST_GATE_V2] ?: false,
            captureProfile = CaptureProfile.fromStored(values[Keys.CAPTURE_PROFILE]),
            interruptSpeechOnVisualChange = values[Keys.INTERRUPT_SPEECH_V2] ?: false,
            sceneDescriptionStyle = SceneDescriptionStyle.fromStored(values[Keys.SCENE_DESCRIPTION_STYLE]),
            speechRate = (values[Keys.SPEECH_RATE] ?: 1.0f)
                .coerceIn(AppSettings.MIN_SPEECH_RATE, AppSettings.MAX_SPEECH_RATE),
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
    override suspend fun setTrustGateEnabled(enabled: Boolean) = update(Keys.TRUST_GATE_V2, enabled)
    override suspend fun setCaptureProfile(profile: CaptureProfile) = update(Keys.CAPTURE_PROFILE, profile.name)
    override suspend fun setInterruptSpeechOnVisualChange(enabled: Boolean) =
        update(Keys.INTERRUPT_SPEECH_V2, enabled)
    override suspend fun setSceneDescriptionStyle(style: SceneDescriptionStyle) =
        update(Keys.SCENE_DESCRIPTION_STYLE, style.name)

    override suspend fun setSpeechRate(rate: Float) =
        update(Keys.SPEECH_RATE, rate.coerceIn(AppSettings.MIN_SPEECH_RATE, AppSettings.MAX_SPEECH_RATE))

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val MODEL = stringPreferencesKey("model")
        val FORCE_CELLULAR = booleanPreferencesKey("force_cellular")
        val SPEECH = booleanPreferencesKey("speech")
        val LOCAL_OCR = booleanPreferencesKey("local_ocr")
        val TRUST_GATE_V2 = booleanPreferencesKey("ocr_trust_gate_v2")
        val CAPTURE_PROFILE = stringPreferencesKey("capture_profile")
        val INTERRUPT_SPEECH_V2 = booleanPreferencesKey("interrupt_speech_on_visual_change_v2")
        val SCENE_DESCRIPTION_STYLE = stringPreferencesKey("scene_description_style")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
    }
}
