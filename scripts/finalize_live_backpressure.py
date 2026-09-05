#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIVE = ROOT / "app/src/main/java/com/abdullah/visionbridge/data/gemini/GeminiLiveSession.kt"
VIEWMODEL = ROOT / "app/src/main/java/com/abdullah/visionbridge/ui/MainViewModel.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"backpressure finalize failed: {label} anchor not found")
    return text.replace(old, new, 1)


def patch_live() -> None:
    text = LIVE.read_text()
    if "STALE_SOCKET_CALLBACK_GUARD_V35" in text:
        return

    # onClosed/onFailure are also delivered for a WebSocket that we deliberately replaced when the
    # user switches reading <-> scene mode. Those callbacks must not release the new socket's active
    # turn. clearSocketIfCurrent below owns completion only when the callback belongs to the current
    # WebSocket instance.
    text = text.replace(
        '            completeActiveTurn(false, "socket_closed")\n            clearSocketIfCurrent(webSocket)\n',
        '            clearSocketIfCurrent(webSocket)\n',
        1,
    )
    text = text.replace(
        '            completeActiveTurn(false, "socket_failure")\n            clearSocketIfCurrent(webSocket)\n',
        '            clearSocketIfCurrent(webSocket)\n',
        1,
    )

    old = '''    private fun clearSocketIfCurrent(webSocket: WebSocket) {
        synchronized(socketLock) {
            if (socket === webSocket) {
                socket = null
                setupReady = null
                setupSucceeded = false
                connectionFingerprint = null
                activeProfile = null
            }
        }
        completeActiveTurn(false, "socket_cleared")
        staleAudioBlocked = false
    }
'''
    new = '''    // STALE_SOCKET_CALLBACK_GUARD_V35: a close callback for a replaced socket is harmless.
    private fun clearSocketIfCurrent(webSocket: WebSocket) {
        var clearedCurrent = false
        synchronized(socketLock) {
            if (socket === webSocket) {
                socket = null
                setupReady = null
                setupSucceeded = false
                connectionFingerprint = null
                activeProfile = null
                clearedCurrent = true
            }
        }
        if (clearedCurrent) {
            completeActiveTurn(false, "socket_cleared")
            staleAudioBlocked = false
        }
    }
'''
    text = replace_once(text, old, new, "stale socket callback guard")

    # If the server interrupts the current turn for any reason, stop that audio epoch as well as
    # releasing the coroutine. This prevents a late PCM tail from leaking into legacy fallback.
    text = replace_once(
        text,
        '''            completeActiveTurn(false, "server_interrupted")
            DiagnosticHub.record(
''',
        '''            completeActiveTurn(false, "server_interrupted")
            audioPlayer.interrupt("live_server_interrupted")
            DiagnosticHub.record(
''',
        "server interruption audio stop",
    )

    LIVE.write_text(text)


def patch_mode_feedback() -> None:
    text = VIEWMODEL.read_text()
    old = '''    fun setMode(mode: AnalysisMode) = viewModelScope.launch {
        container.settingsRepository.setMode(mode)
    }
'''
    new = '''    fun setMode(mode: AnalysisMode) = viewModelScope.launch {
        container.settingsRepository.setMode(mode)
        message.value = when (mode) {
            AnalysisMode.TEXT_READING -> "تم تفعيل قراءة النص"
            AnalysisMode.SCENE_DESCRIPTION -> "تم تفعيل وصف المشهد"
        }
        DiagnosticHub.record("MODE_SELECTED", mapOf("mode" to mode.name))
    }
'''
    if new not in text:
        text = replace_once(text, old, new, "mode feedback")
    VIEWMODEL.write_text(text)


patch_live()
patch_mode_feedback()
print("Finalized VisionBridge 3.5 backpressure safeguards")
