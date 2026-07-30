from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


coordinator_path = Path("app/src/main/java/com/abdullah/visionbridge/capture/FrameAnalysisCoordinator.kt")
coordinator = coordinator_path.read_text(encoding="utf-8")

coordinator = replace_once(
    coordinator,
    """        if (localText.isNotBlank() && generationAtCapture == visualGeneration.get()) {
""",
    """        if (
            localText.isNotBlank() &&
            VisualChangeDeliveryPolicy.mayDeliver(
                generationAtCapture = generationAtCapture,
                currentGeneration = visualGeneration.get(),
                interruptOnVisualChange = settings.interruptSpeechOnVisualChange,
            )
        ) {
""",
    "local OCR delivery policy",
)

coordinator = replace_once(
    coordinator,
    """        val targetChanged = generationAtCapture != visualGeneration.get()
        val staleMustStop = settings.mode == AnalysisMode.TEXT_READING || settings.interruptSpeechOnVisualChange
        if (targetChanged && staleMustStop) {
""",
    """        val currentGeneration = visualGeneration.get()
        if (
            !VisualChangeDeliveryPolicy.mayDeliver(
                generationAtCapture = generationAtCapture,
                currentGeneration = currentGeneration,
                interruptOnVisualChange = settings.interruptSpeechOnVisualChange,
            )
        ) {
""",
    "stream delivery policy",
)

coordinator = replace_once(
    coordinator,
    """        val targetCurrent = generationAtCapture == visualGeneration.get()
        val mayPublishStaleScene =
            settings.mode == AnalysisMode.SCENE_DESCRIPTION && !settings.interruptSpeechOnVisualChange
        if (targetCurrent || mayPublishStaleScene) {
""",
    """        val mayDeliver = VisualChangeDeliveryPolicy.mayDeliver(
            generationAtCapture = generationAtCapture,
            currentGeneration = visualGeneration.get(),
            interruptOnVisualChange = settings.interruptSpeechOnVisualChange,
        )
        if (mayDeliver) {
""",
    "final result delivery policy",
)

old_method = """    /**
     * Speech interruption and network supersession are separate concerns. A user may choose to let
     * an utterance finish, but an OCR request for a page they no longer view must never hold the only
     * cloud lane. Text requests are therefore cancelled on a real target change regardless of the
     * speech setting; live scene requests retain the original user-controlled behaviour.
     */
    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        val newGeneration = visualGeneration.incrementAndGet()
        DiagnosticHub.record(
            "VISUAL_TARGET_CHANGED",
            mapOf("newGeneration" to newGeneration, "interruptSpeech" to interruptSpeech),
        )
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L

        if (interruptSpeech) tts.onVisualTargetChanged(true)

        synchronized(cloudQueueLock) {
            val shouldSupersedeCloud = activeCloudMode == AnalysisMode.TEXT_READING || interruptSpeech
            if (!shouldSupersedeCloud) return@synchronized

            pendingCloudFrame?.let { pending ->
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    pending.trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to "visual_target_changed",
                            "activeCloudMode" to activeCloudMode?.name,
                        ),
                    ),
                )
                pending.bitmap.recycle()
            }
            pendingCloudFrame = null
            cloudJob?.let { active ->
                DiagnosticHub.record(
                    "CLOUD_ACTIVE_REQUEST_SUPERSEDED",
                    mapOf(
                        "activeCloudMode" to activeCloudMode?.name,
                        "newGeneration" to newGeneration,
                        "interruptSpeech" to interruptSpeech,
                    ),
                )
                active.cancel(CancellationException("visual_target_changed"))
            }
            cloudJob = null
            activeCloudMode = null
        }
    }
"""

new_method = """    /**
     * Visual generations always advance so queue quality and diagnostics know that the camera moved.
     * Cancelling an active Gemini request is controlled only by the user's interruption setting.
     * When interruption is disabled, the current response finishes while the queue retains only the
     * newest pending frame. This prevents camera movement from cancelling every request before the
     * server has time to answer.
     */
    fun onVisualTargetChanged(interruptSpeech: Boolean) {
        val newGeneration = visualGeneration.incrementAndGet()
        DiagnosticHub.record(
            "VISUAL_TARGET_CHANGED",
            mapOf("newGeneration" to newGeneration, "interruptSpeech" to interruptSpeech),
        )
        lastCloudOcrAt = 0L
        lastSceneAt = 0L
        lastCloudSnapshotAt = 0L

        if (interruptSpeech) tts.onVisualTargetChanged(true)

        synchronized(cloudQueueLock) {
            if (!VisualChangeDeliveryPolicy.shouldCancelActiveRequest(interruptSpeech)) {
                val hasActiveRequest = cloudJob?.isActive == true
                val hasPendingFrame = pendingCloudFrame != null
                if (hasActiveRequest || hasPendingFrame) {
                    DiagnosticHub.record(
                        "CLOUD_ACTIVE_REQUEST_PRESERVED",
                        mapOf(
                            "activeCloudMode" to activeCloudMode?.name,
                            "newGeneration" to newGeneration,
                            "interruptSpeech" to false,
                            "hasActiveRequest" to hasActiveRequest,
                            "hasPendingFrame" to hasPendingFrame,
                            "reason" to "visual_change_interruption_disabled",
                        ),
                    )
                }
                return@synchronized
            }

            pendingCloudFrame?.let { pending ->
                DiagnosticHub.record(
                    "CLOUD_FRAME_DROPPED",
                    pending.trace.fieldsOrEmpty(
                        mapOf(
                            "reason" to "visual_target_changed",
                            "activeCloudMode" to activeCloudMode?.name,
                        ),
                    ),
                )
                pending.bitmap.recycle()
            }
            pendingCloudFrame = null
            cloudJob?.let { active ->
                DiagnosticHub.record(
                    "CLOUD_ACTIVE_REQUEST_SUPERSEDED",
                    mapOf(
                        "activeCloudMode" to activeCloudMode?.name,
                        "newGeneration" to newGeneration,
                        "interruptSpeech" to true,
                    ),
                )
                active.cancel(CancellationException("visual_target_changed"))
            }
            cloudJob = null
            activeCloudMode = null
        }
    }
"""

coordinator = replace_once(
    coordinator,
    old_method,
    new_method,
    "visual target cancellation method",
)
coordinator_path.write_text(coordinator, encoding="utf-8")

policy_path = Path("app/src/main/java/com/abdullah/visionbridge/capture/VisualChangeDeliveryPolicy.kt")
policy_path.write_text(
    '''package com.abdullah.visionbridge.capture

/**
 * Defines whether work from an older visual generation may finish and reach the user.
 *
 * Disabling interruption is a deliberate promise to let the active response complete. The
 * coordinator still retains only the newest pending frame, so it catches up without building a
 * backlog and without repeatedly cancelling the network request.
 */
internal object VisualChangeDeliveryPolicy {
    fun mayDeliver(
        generationAtCapture: Long,
        currentGeneration: Long,
        interruptOnVisualChange: Boolean,
    ): Boolean = generationAtCapture == currentGeneration || !interruptOnVisualChange

    fun shouldCancelActiveRequest(interruptOnVisualChange: Boolean): Boolean =
        interruptOnVisualChange
}
''',
    encoding="utf-8",
)

test_path = Path("app/src/test/java/com/abdullah/visionbridge/capture/VisualChangeDeliveryPolicyTest.kt")
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(
    '''package com.abdullah.visionbridge.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualChangeDeliveryPolicyTest {
    @Test
    fun `current generation is always deliverable`() {
        assertTrue(VisualChangeDeliveryPolicy.mayDeliver(7L, 7L, true))
        assertTrue(VisualChangeDeliveryPolicy.mayDeliver(7L, 7L, false))
    }

    @Test
    fun `older generation completes when interruption is disabled`() {
        assertTrue(VisualChangeDeliveryPolicy.mayDeliver(7L, 8L, false))
        assertFalse(VisualChangeDeliveryPolicy.shouldCancelActiveRequest(false))
    }

    @Test
    fun `older generation is rejected when interruption is enabled`() {
        assertFalse(VisualChangeDeliveryPolicy.mayDeliver(7L, 8L, true))
        assertTrue(VisualChangeDeliveryPolicy.shouldCancelActiveRequest(true))
    }
}
''',
    encoding="utf-8",
)

gradle_path = Path("app/build.gradle.kts")
gradle = gradle_path.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode = 13", "versionCode = 14", "version code")
gradle = replace_once(gradle, 'versionName = "1.10.1"', 'versionName = "1.10.2"', "version name")
gradle_path.write_text(gradle, encoding="utf-8")
