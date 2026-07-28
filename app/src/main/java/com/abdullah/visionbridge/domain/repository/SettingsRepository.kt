package com.abdullah.visionbridge.domain.repository

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import com.abdullah.visionbridge.domain.model.CaptureProfile
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setMode(mode: AnalysisMode)
    suspend fun setModel(model: String)
    suspend fun setForceCellular(enabled: Boolean)
    suspend fun setSpeechEnabled(enabled: Boolean)
    suspend fun setLocalOcrEnabled(enabled: Boolean)
    suspend fun setTrustGateEnabled(enabled: Boolean)
    suspend fun setCaptureProfile(profile: CaptureProfile)
    suspend fun setInterruptSpeechOnVisualChange(enabled: Boolean)
    suspend fun setSceneDescriptionStyle(style: SceneDescriptionStyle)
    suspend fun setSpeechRate(rate: Float)
}
