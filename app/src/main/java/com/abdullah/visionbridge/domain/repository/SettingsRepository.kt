package com.abdullah.visionbridge.domain.repository

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.LocalReadingQuality
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setMode(mode: AnalysisMode)
    suspend fun setModel(model: String)
    suspend fun setForceCellular(enabled: Boolean)
    suspend fun setSpeechEnabled(enabled: Boolean)
    suspend fun setTrustGateEnabled(enabled: Boolean)
    suspend fun setCaptureProfile(profile: CaptureProfile)
    suspend fun setInterruptSpeechOnVisualChange(enabled: Boolean)
    suspend fun setSceneDescriptionStyle(style: SceneDescriptionStyle)
    suspend fun setUseLocalOcr(enabled: Boolean)
    suspend fun setDescribeAlongsideText(enabled: Boolean)

    suspend fun setLocalReadingQuality(quality: LocalReadingQuality)
    /** Store the frame behind a failure in the diagnostic bundle. Off by default; see AppSettings. */
    suspend fun setCaptureFailureEvidence(enabled: Boolean)

    suspend fun setSpeechRate(rate: Float)
}
