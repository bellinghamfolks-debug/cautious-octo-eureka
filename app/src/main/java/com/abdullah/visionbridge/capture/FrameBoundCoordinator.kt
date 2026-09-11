package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.gemini.FrameTurnTransport
import com.abdullah.visionbridge.data.gemini.LiveFrameEncoder
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.vision.TextGroundingGate
import com.abdullah.visionbridge.domain.model.*
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** A single analysis lane; speech never owns its capacity. The service owns one pending candidate. */
class FrameBoundCoordinator(private val transport: FrameTurnTransport, private val local: PaddleOcrEngine,
                            private val keys: ApiKeyStore, private val tts: BilingualTtsEngine,
                            private val runtime: CaptureRuntime) {
    data class Candidate(val turn: AnalysisTurn,val trace: DiagnosticTrace,val settings: AppSettings,
                         val quality: QualityRetryPolicy.Quality,val blackRatio: Double,val change: Double)
    private val gate=runtime.turnGate
    private val lane=Mutex()
    private val policy=QualityRetryPolicy()
    private val encoder=LiveFrameEncoder()
    @Volatile private var activeJob: Job?=null
    private var previous: FloatArray?=null
    private var stableSince=0L
    private var unavailableAnnounced=false
    private var sceneText=""
    private var sceneReliable=false

    @Synchronized fun candidate(bitmap:Bitmap,trace:DiagnosticTrace,settings:AppSettings):Candidate {
        val plane=BitmapFrames.aspectPlane(bitmap,256);val pixels=plane.luma
        val prior=previous
        val difference=if(prior?.size==pixels.size) pixels.indices.sumOf { abs(pixels[it]-prior[it]).toDouble() }/pixels.size else 255.0
        val now=SystemClock.elapsedRealtime()
        if(prior==null || difference>7) stableSince=now
        previous=pixels
        val mean=pixels.average();val contrast=sqrt(pixels.sumOf { (it-mean)*(it-mean) }/pixels.size)
        var lap=0.0;var edges=0;var boundaryEdges=0
        for(y in 1 until plane.height-1) for(x in 1 until plane.width-1) {
            val i=y*plane.width+x
            val d=4*pixels[i]-pixels[i-1]-pixels[i+1]-pixels[i-plane.width]-pixels[i+plane.width]
            lap+=d*d
            if(abs(d)>30) {
                edges++
                if(x<=2 || y<=2 || x>=plane.width-3 || y>=plane.height-3) boundaryEdges++
            }
        }
        // Conservative edge-contact proxy, not proof that every OCR box is complete.
        val completeness=1.0-(boundaryEdges.toDouble()/edges.coerceAtLeast(1)*4).coerceIn(0.0,.6)
        val q=QualityRetryPolicy.Quality(lap/pixels.size,contrast,edges.toDouble()/pixels.size,completeness,now-stableSince)
        val turn=AnalysisTurn(trace.traceId,trace.traceId,trace.frameId,gate.generation(),settings.mode,
            trace.capturedAtEpochMs,trace.capturedAtElapsedNanos,
            if(settings.useLocalOcr && settings.mode==AnalysisMode.TEXT_READING) "PP-OCRv5" else FrameTurnTransport.MODEL,
            FrameTurnTransport.PROMPT_VERSION,UUID.randomUUID().toString())
        return Candidate(turn,trace.copy(turn=turn),settings,q,pixels.count { it<8 }.toDouble()/pixels.size,difference)
    }

    suspend fun process(bitmap:Bitmap,c:Candidate)=lane.withLock {
        val capture=c.turn;val settings=c.settings
        if(!runtime.analysing.value) { skip(c,"analysis_paused");return@withLock }
        if(capture.visualGeneration!=gate.generation()) { skip(c,"obsolete_candidate");return@withLock }
        if(c.blackRatio>=.94) {
            skip(c,"unavailable_image")
            if(!unavailableAnnounced) {
                unavailableAnnounced=true;onVisualTargetChanged(true)
                runtime.notice("الصورة غير متاحة");tts.speakFeedback("الصورة غير متاحة")
            }
            return@withLock
        }
        unavailableAnnounced=false
        val textMode=settings.mode==AnalysisMode.TEXT_READING
        val decision=if(textMode) policy.consider(c.quality,SystemClock.elapsedRealtime(),settings.captureProfile==CaptureProfile.STABLE)
            else QualityRetryPolicy.Decision(!sceneReliable || c.change>=2.5,"scene_duplicate")
        if(!decision.submit) { skip(c,decision.reason);return@withLock }
        activeJob=currentCoroutineContext()[Job]
        if(!gate.activate(capture) {
            tts.invalidateVisualContent();runtime.clearVisualResult()
            policy.submitted(c.quality,SystemClock.elapsedRealtime());runtime.processing(true)
        }) { activeJob=null;return@withLock }
        DiagnosticHub.record("TURN_ACTIVATED",capture.fields())
        var bound:AnalysisTurn?=null;var readAccepted=false;var spokenScene=""
        try {
            withContext(c.trace) {
                val encoded=encoder.encode(bitmap,settings)
                currentCoroutineContext().ensureActive()
                val evidence=if(textMode) {
                    local.ensureLoaded().getOrThrow()
                    val groundingStarted=SystemClock.elapsedRealtimeNanos()
                    val exact=checkNotNull(BitmapFactory.decodeByteArray(encoded.bytes,0,encoded.bytes.size))
                    val result=try { local.read(exact,LocalReadingQuality.MAXIMUM) } finally { exact.recycle() }
                    DiagnosticHub.record("LOCAL_GROUNDING_COMPLETED",capture.fields()+mapOf(
                        "durationMs" to (SystemClock.elapsedRealtimeNanos()-groundingStarted)/1e6,
                        "detectedBoxes" to result.detectedBoxCount,"localConfidence" to result.confidence,
                        "imageHash" to encoded.imageHash))
                    TextGroundingGate.Evidence(result.text,result.confidence,result.detectedBoxCount)
                } else TextGroundingGate.Evidence("",0f,0)
                fun accept(o:FrameTurnTransport.Output) {
                    if(gate.rejection(o.turn)!=null) { DiagnosticHub.record("RESULT_DROPPED",o.turn.fields());return }
                    if(textMode) {
                        if(readAccepted) return
                        val d=TextGroundingGate.evaluate(o.text,o.confidence,o.legible,o.inferred,evidence)
                        DiagnosticHub.record("TEXT_GROUNDING_DECISION",o.turn.fields()+mapOf("accepted" to d.accepted,"retry" to d.retry,"reason" to d.reason))
                        if(!d.accepted) { gate.commit(o.turn) { policy.result(false);runtime.notice("النص غير واضح؛ وجّه الكاميرا بثبات") };return }
                        gate.commit(o.turn) {
                            policy.result(true);readAccepted=true
                            val r=AnalysisResult(o.text,if(settings.useLocalOcr)AnalysisSource.LOCAL_OCR else AnalysisSource.GEMINI,turn=o.turn)
                            if(runtime.result(r)&&settings.speechEnabled)tts.speakTurn(r,settings.speechRate,"READ_TEXT")
                        }
                    } else {
                        if(!o.legible||o.inferred||o.confidence<90||o.text.isBlank()||o.text.startsWith("NO_"))return
                        val maxWords=if(settings.sceneDescriptionStyle==SceneDescriptionStyle.BRIEF)32 else 120
                        if(o.text.split(Regex("\\s+")).size>maxWords)return
                        gate.commit(o.turn) {
                            val r=AnalysisResult(o.text,AnalysisSource.GEMINI,turn=o.turn)
                            if(runtime.result(r)&&settings.speechEnabled && o.text.startsWith(spokenScene)) {
                                val delta=o.text.removePrefix(spokenScene).trim()
                                if(delta.isNotBlank())tts.speakTurn(r.copy(text=delta),settings.speechRate,"SCENE_DESCRIPTION")
                            }
                            spokenScene=o.text;sceneText=o.text;sceneReliable=true
                        }
                    }
                }
                if(settings.useLocalOcr&&textMode) {
                    val turn=capture.copy(submittedAtNanos=SystemClock.elapsedRealtimeNanos(),imageHash=encoded.imageHash)
                    if(!gate.bindSubmission(turn))return@withContext
                    bound=turn;DiagnosticHub.record("LOCAL_FRAME_BOUND",turn.fields())
                    accept(FrameTurnTransport.Output(turn,evidence.text,"",(evidence.confidence*100).toInt(),true,false))
                } else {
                    val key=keys.get();check(!key.isNullOrBlank()) { "مفتاح Gemini غير موجود" }
                    val output=transport.analyze(capture,encoded,settings,key,onSubmitted={
                        if(gate.bindSubmission(it)) { bound=it;true } else false
                    },onPartial={accept(it)})
                    accept(output)
                    if(textMode&&readAccepted&&output.tail.isNotBlank()&&output.tail.split(Regex("\\s+")).size<=28) {
                        gate.commit(output.turn) {
                            val r=AnalysisResult(output.text,AnalysisSource.GEMINI,sceneTail=output.tail,turn=output.turn)
                            runtime.result(r)
                            if(settings.speechEnabled)tts.speakTurn(r.copy(text=output.tail),settings.speechRate,"SCENE_TAIL")
                        }
                    }
                }
            }
        } catch(e:CancellationException) {
            DiagnosticHub.record("TURN_CANCELLED_OBSOLETE",(bound?:capture).fields());throw e
        } catch(e:Exception) {
            val turn=bound
            if(turn!=null)gate.commit(turn) { runtime.error("تعذر تحليل الصورة الحالية") }
            DiagnosticHub.record("FRAME_ANALYSIS_FAILURE",(bound?:capture).fields()+mapOf("errorType" to e.javaClass.simpleName))
        } finally {
            bound?.let { gate.commit(it) { runtime.processing(false) } }
            activeJob=null
        }
    }

    fun onVisualTargetChanged(interruptSpeech:Boolean) {
        gate.invalidate { runtime.clearVisualResult();tts.invalidateVisualContent();policy.reset() }
        activeJob?.cancel()
        synchronized(this) { previous=null;stableSince=0;sceneText="";sceneReliable=false }
        DiagnosticHub.record("VISUAL_GENERATION_CHANGED",mapOf("visualGeneration" to gate.generation(),"interruptPreference" to interruptSpeech))
    }
    suspend fun speakNotice(message:String)=tts.speakFeedback(message)
    fun stopSpeech()=onVisualTargetChanged(true)
    fun reset()=onVisualTargetChanged(true)
    private fun skip(c:Candidate,reason:String) {
        DiagnosticHub.record("FRAME_SKIPPED",c.turn.fields()+mapOf("reason" to reason))
        if(reason.contains("quality")||reason=="awaiting_stability")DiagnosticHub.record("FRAME_SUPPRESSED_QUALITY",c.turn.fields())
        if(reason.contains("duplicate"))DiagnosticHub.record("FRAME_SUPPRESSED_DUPLICATE",c.turn.fields())
    }
}
