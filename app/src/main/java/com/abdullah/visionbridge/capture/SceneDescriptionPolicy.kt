package com.abdullah.visionbridge.capture

import com.abdullah.visionbridge.data.vision.TextGroundingGate
import com.abdullah.visionbridge.domain.model.SceneDescriptionStyle

/** Per-generation scene decisions. Optical text acceptance never satisfies this policy. */
class SceneDescriptionPolicy {
    data class Decision(val accepted:Boolean,val reason:String,val delta:String="")
    private var generation=-1L
    private var style:SceneDescriptionStyle?=null
    private var acceptedText=""
    private var lastRequestedAt:Long?=null

    @Synchronized fun shouldProbe(gen:Long,requestedStyle:SceneDescriptionStyle,now:Long,
                                 compensatedDifference:Double?,chromaDifference:Double?):Decision {
        if(gen!=generation || style!=requestedStyle) resetTo(gen,requestedStyle)
        if(acceptedText.isEmpty()) return Decision(true,"first_valid_scene_or_retry")
        // Motion compensation comes from Smart Target. Raw frame differences (shake/zoom/light)
        // cannot by themselves authorize another description.
        val changed=(compensatedDifference ?: 0.0)>=.08 || (chromaDifference ?: 0.0)>=16
        if(!changed) return Decision(false,"scene_compensated_duplicate")
        if(lastRequestedAt?.let { now-it<700 }==true) return Decision(false,"scene_probe_cooldown")
        return Decision(true,"scene_local_change_probe")
    }

    @Synchronized fun submitted(now:Long) { lastRequestedAt=now }

    /** Prefixes are accepted only for this stream. A different stream starts with an empty prefix. */
    @Synchronized fun output(text:String,confidence:Int,legible:Boolean,inferred:Boolean,
                             requestedStyle:SceneDescriptionStyle,streamPrefix:String):Decision {
        if(!legible || inferred || confidence<90 || text.isBlank() || text.trim().startsWith("NO_"))
            return Decision(false,"scene_unreliable")
        if(text.trim().split(Regex("\\s+")).size>if(requestedStyle==SceneDescriptionStyle.BRIEF)32 else 120)
            return Decision(false,"scene_word_budget")
        if(!text.startsWith(streamPrefix)) return Decision(false,"scene_stream_rewritten")
        if(streamPrefix.isEmpty() && acceptedText.isNotEmpty() &&
            TextGroundingGate.canonical(text)==TextGroundingGate.canonical(acceptedText))
            return Decision(false,"scene_semantic_duplicate")
        val delta=text.removePrefix(streamPrefix).trim()
        if(delta.isEmpty()) return Decision(false,"scene_repeated_chunk")
        acceptedText=text
        return Decision(true,"scene_current_prefix",delta)
    }

    @Synchronized fun reset() { resetTo(-1,null) }
    private fun resetTo(gen:Long,s:SceneDescriptionStyle?) {
        generation=gen;style=s;acceptedText="";lastRequestedAt=null
    }
}
