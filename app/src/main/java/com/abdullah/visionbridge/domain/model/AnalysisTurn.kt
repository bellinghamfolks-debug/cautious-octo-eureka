package com.abdullah.visionbridge.domain.model

/** Immutable capture identity. Binding the encoded image produces a new value, never a new owner. */
data class AnalysisTurn(
    val turnId: String,
    val traceId: String,
    val frameId: String,
    val visualGeneration: Long,
    val mode: AnalysisMode,
    val capturedAt: Long,
    val capturedAtNanos: Long,
    val model: String,
    val promptVersion: String,
    val sessionId: String,
    val submittedAtNanos: Long? = null,
    val imageHash: String? = null,
    val opportunityCapturedAtNanos:Long = capturedAtNanos,
) {
    init {
        require(listOf(turnId, traceId, frameId, model, promptVersion, sessionId).all { it.isNotBlank() })
        require(visualGeneration >= 0 && capturedAtNanos >= 0)
        require(opportunityCapturedAtNanos in 0..capturedAtNanos)
        require((submittedAtNanos == null) == (imageHash == null))
        require(submittedAtNanos == null || submittedAtNanos >= capturedAtNanos)
        require(imageHash == null || imageHash.matches(Regex("[0-9a-f]{64}")))
    }

    fun sameCapture(other: AnalysisTurn): Boolean =
        copy(submittedAtNanos = null, imageHash = null) ==
            other.copy(submittedAtNanos = null, imageHash = null)

    fun fields(): Map<String, Any?> = mapOf(
        "turnId" to turnId, "traceId" to traceId, "frameId" to frameId,
        "visualGeneration" to visualGeneration, "mode" to mode.name,
        "capturedAtEpochMs" to capturedAt, "capturedAtElapsedNanos" to capturedAtNanos,
        "submittedAtElapsedNanos" to submittedAtNanos, "model" to model,
        "promptVersion" to promptVersion, "transportSessionId" to sessionId,
        "imageHash" to imageHash,
        "opportunityCapturedAtElapsedNanos" to opportunityCapturedAtNanos,
    )
}
