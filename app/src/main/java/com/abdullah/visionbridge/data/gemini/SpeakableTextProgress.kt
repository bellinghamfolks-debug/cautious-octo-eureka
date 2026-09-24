package com.abdullah.visionbridge.data.gemini

/** Uses the speech buffer's natural blocks while preserving literal cumulative model text. */
class SpeakableTextProgress {
    private val buffer=StreamingSpeechBuffer(StreamingSpeechBuffer.Profile.RESPONSIVE)
    private var observed=""
    private var end=0
    fun update(text:String, complete:Boolean):Int {
        if(!text.startsWith(observed))return end // Coordinator rejects rewritten accepted prefixes.
        val blocks=buffer.append(text.removePrefix(observed),false)+if(complete)buffer.finish() else emptyList()
        observed=text
        for(block in blocks) {
            val start=text.indexOf(block,end)
            if(start>=end)end=start+block.length
        }
        return end
    }
}
