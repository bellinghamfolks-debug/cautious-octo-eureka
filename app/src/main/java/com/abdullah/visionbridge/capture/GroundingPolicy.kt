package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.AppSettings

object GroundingPolicy {
    fun required(settings: AppSettings): Boolean = settings.mode == AnalysisMode.TEXT_READING &&
        (settings.useLocalOcr || settings.trustGateEnabled)
    fun advisory(settings: AppSettings): Boolean = settings.mode == AnalysisMode.TEXT_READING &&
        !settings.useLocalOcr && !settings.trustGateEnabled
}
