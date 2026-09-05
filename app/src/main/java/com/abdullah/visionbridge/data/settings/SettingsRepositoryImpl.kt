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
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import com.abdullah.visionbridge.domain.model.ViewportMode
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
            // Live-only keeps the retired network override off.
            forceCellular = false,
            speechEnabled = values[Keys.SPEECH] ?: true,
            // Live Accuracy Guard is always on.
            trustGateEnabled = false,
            captureProfile = CaptureProfile.fromStored(values[Keys.CAPTURE_PROFILE]),
            // V3 intentionally does not inherit the old raw-interrupt toggle. Smart interruption is
            // a different and safer policy, so upgrades start with it enabled.
            interruptSpeechOnVisualChange = values[Keys.SMART_TARGET_INTERRUPTION_V3] ?: true,
            sceneDescriptionStyle = SceneDescriptionStyle.fromStored(values[Keys.SCENE_DESCRIPTION_STYLE]),
            useLocalOcr = values[Keys.USE_LOCAL_OCR] ?: false,
            describeAlongsideText = values[Keys.DESCRIBE_ALONGSIDE_TEXT] ?: false,
            localReadingQuality = LocalReadingQuality.fromStored(values[Keys.LOCAL_READING_QUALITY]),
            viewportMode = ViewportMode.fromStored(values[Keys.VIEWPORT_MODE]),
            captureFailureEvidence = values[Keys.CAPTURE_FAILURE_EVIDENCE] ?: false,
            speechRate = (values[Keys.SPEECH_RATE] ?: 1.0f)
                .coerceIn(AppSettings.MIN_SPEECH_RATE, AppSettings.MAX_SPEECH_RATE),
        )
    }

    override suspend fun setMode(mode: AnalysisMode) = update(Keys.MODE, mode.name)

    override suspend fun setModel(model: String) {
        require(model in AppSettings.SUPPORTED_MODELS) { "نموذج Gemini غير مدعوم" }
        update(Keys.MODEL, model)
    }

    override suspend fun setForceCellular(enabled: Boolean) {
        update(Keys.FORCE_CELLULAR, false)
    }

    override suspend fun setSpeechEnabled(enabled: Boolean) = update(Keys.SPEECH, enabled)

    override suspend fun setTrustGateEnabled(enabled: Boolean) {
        update(Keys.TRUST_GATE_V2, false)
    }

    override suspend fun setCaptureProfile(profile: CaptureProfile) = update(Keys.CAPTURE_PROFILE, profile.name)
    override suspend fun setInterruptSpeechOnVisualChange(enabled: Boolean) =
        update(Keys.SMART_TARGET_INTERRUPTION_V3, enabled)
    override suspend fun setSceneDescriptionStyle(style: SceneDescriptionStyle) =
        update(Keys.SCENE_DESCRIPTION_STYLE, style.name)
    override suspend fun setUseLocalOcr(enabled: Boolean) = update(Keys.USE_LOCAL_OCR, enabled)
    override suspend fun setDescribeAlongsideText(enabled: Boolean) =
        update(Keys.DESCRIBE_ALONGSIDE_TEXT, enabled)
    override suspend fun setLocalReadingQuality(quality: LocalReadingQuality) =
        update(Keys.LOCAL_READING_QUALITY, quality.name)
    override suspend fun setViewportMode(mode: ViewportMode) = update(Keys.VIEWPORT_MODE, mode.name)
    override suspend fun setCaptureFailureEvidence(enabled: Boolean) =
        update(Keys.CAPTURE_FAILURE_EVIDENCE, enabled)
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
        val TRUST_GATE_V2 = booleanPreferencesKey("ocr_trust_gate_v2")
        val CAPTURE_PROFILE = stringPreferencesKey("capture_profile")
        val INTERRUPT_SPEECH_V2 = booleanPreferencesKey("interrupt_speech_on_visual_change_v2")
        val SMART_TARGET_INTERRUPTION_V3 = booleanPreferencesKey("smart_target_interruption_v3")
        val SCENE_DESCRIPTION_STYLE = stringPreferencesKey("scene_description_style")
        val USE_LOCAL_OCR = booleanPreferencesKey("use_local_ocr")
        val DESCRIBE_ALONGSIDE_TEXT = booleanPreferencesKey("describe_alongside_text")
        val LOCAL_READING_QUALITY = stringPreferencesKey("local_reading_quality")
        val VIEWPORT_MODE = stringPreferencesKey("viewport_mode_v1")
        val CAPTURE_FAILURE_EVIDENCE = booleanPreferencesKey("capture_failure_evidence")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
    }
}
