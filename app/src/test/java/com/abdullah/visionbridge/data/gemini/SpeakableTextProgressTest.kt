package com.abdullah.visionbridge.data.gemini

import org.junit.Assert.*
import org.junit.Test

class SpeakableTextProgressTest {
    @Test fun fullSentenceCanStartSpeechBeforeTurnEndsWithoutAnyNewline() {
        val progress=SpeakableTextProgress()
        assertEquals(0,progress.update("Current visible",false))
        val sentence="Current visible product text."
        assertEquals(sentence.length,progress.update(sentence+" More",false))
        val final=sentence+" More visible text"
        assertEquals(final.length,progress.update(final,true))
    }
    @Test fun preservesOriginalMixedTextAndDoesNotInventEndOfPartialWord() {
        val progress=SpeakableTextProgress()
        val first="هذا هو النص الحالي."
        val all=first+"\nABC 123 complete."
        val end=progress.update(all,false)
        assertEquals(all.length,end)
        assertEquals(end,progress.update(all+" par",false))
    }
}
