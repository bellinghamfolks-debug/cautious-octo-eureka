package com.abdullah.visionbridge.capture.vision

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import kotlin.math.abs

/** Holds geometry only. Unchanged external controls keep a confirmed camera region usable
 * when its feed goes black. Rotation and layout changes invalidate the old geometry. */
class ViewportSessionResolver {
    private data class Held(val key:List<Any>,val rect:Viewport.Rect,val chrome:FloatArray)
    private var held:Held?=null
    @Synchronized fun resolve(plane:ImagePlane,width:Int,height:Int,mode:ViewportMode,
                              analysisMode:AnalysisMode):ViewportResolver.Resolution {
        val key=listOf(width,height,mode,analysisMode)
        val measured=ViewportResolver.resolve(plane,width,height,mode,analysisMode)
        val previous=held?.takeIf { it.key==key }
        if(previous!=null) {
            val chrome=outsideSamples(plane,previous.rect)
            if(chrome.size==previous.chrome.size && chrome.size>=12 &&
                chrome.indices.sumOf { abs(chrome[it]-previous.chrome[it]).toDouble() }/chrome.size<6)
                return ViewportResolver.Resolution(previous.rect,"CONFIRMED_EXTERNAL_LAYOUT")
        }
        val chrome=outsideSamples(plane,measured.rect)
        held=if(!measured.strategy.contains("UNRESOLVED") && chrome.size>=12)
            Held(key,measured.rect,chrome) else null
        return measured
    }
    @Synchronized fun reset() { held=null }
    private fun outsideSamples(p:ImagePlane,r:Viewport.Rect):FloatArray {
        val values=ArrayList<Float>()
        for(y in 0 until 18)for(x in 0 until 32) {
            val nx=(x+.5f)/32;val ny=(y+.5f)/18
            if(nx>=r.left-.02f && nx<=r.right+.02f && ny>=r.top-.02f && ny<=r.bottom+.02f)continue
            values+=p.luma[(ny*p.height).toInt().coerceAtMost(p.height-1)*p.width+
                (nx*p.width).toInt().coerceAtMost(p.width-1)]
        }
        return values.toFloatArray()
    }
}
