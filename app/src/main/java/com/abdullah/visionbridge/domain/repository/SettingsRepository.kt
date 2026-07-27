package com.abdullah.visionbridge.domain.repository

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setMode(mode: AnalysisMode)
    suspend fun setModel(model: String)
    suspend fun setForceCellular(enabled: Boolean)
    suspend fun setSpeechEnabled(enabled: Boolean)
    suspend fun setLocalOcrEnabled(enabled: Boolean)
}
