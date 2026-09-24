package com.abdullah.visionbridge.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.abdullah.visionbridge.data.diagnostics.DiagnosticHub
import com.abdullah.visionbridge.data.diagnostics.DiagnosticTrace
import com.abdullah.visionbridge.data.diagnostics.FrameStages
import com.abdullah.visionbridge.data.gemini.FrameTurnTransport
import com.abdullah.visionbridge.data.gemini.LiveFrameEncoder
import com.abdullah.visionbridge.data.paddleocr.PaddleOcrEngine
import com.abdullah.visionbridge.data.speech.BilingualTtsEngine
import com.abdullah.visionbridge.data.vision.TextGroundingGate
import com.abdullah.visionbridge.domain.model.*
import com.abdullah.visionbridge.domain.repository.ApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
                            private val runtime: CaptureRuntime, private val advisoryLocal: PaddleOcrEngine) {
    data class Candidate(val turn: AnalysisTurn,val trace: DiagnosticTrace,val settings: AppSettings,
                         val quality: QualityRetryPolicy.Quality,val blackRatio: Double,val change: Double,
                         val compensatedDifference:Double?,val chromaDifference:Double?)
    private val gate=runtime.turnGate
    private val lane=Mutex()
    private val policy=QualityRetryPolicy()
    private val encoder=LiveFrameEncoder()
    @Volatile private var activeJob: Job?=null
    private var previous: FloatArray?=null
    private var stableSince=0L
    private var opportunityCapturedAt:Long?=null
    private val availability=VisualAvailability()
    @Volatile private var acceptedOpticalText=""
    private var configurationNoticeSpoken = false
    private val scenePolicy=SceneDescriptionPolicy()
    private val advisoryLane=OptionalGroundingLane()

    @Synchronized fun candidate(bitmap:Bitmap,trace:DiagnosticTrace,settings:AppSettings,
                                target:VisualTargetTracker.Decision?=null):Candidate {
        val qualityStarted=SystemClock.elapsedRealtimeNanos()
        val plane=BitmapFrames.aspectPlane(bitmap,256);val pixels=plane.luma
        val prior=previous
        val difference=if(prior?.size==pixels.size) pixels.indices.sumOf { abs(pixels[it]-prior[it]).toDouble() }/pixels.size else 255.0
        val now=SystemClock.elapsedRealtime()
        val motionCompensatedStable=target!=null && !target.targetChanged &&
            target.reason!="awaiting_target_consensus" && (target.dissimilarity ?: 1.0)<=.08 &&
            (target.coverage ?: 0.0)>=.6
        if(prior==null || (difference>7 && !motionCompensatedStable)) stableSince=now
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
        val eligible=settings.mode==AnalysisMode.SCENE_DESCRIPTION || (q.sharpness>=12 && q.contrast>=10 &&
            q.cropCompleteness>=.5 && (settings.captureProfile!=CaptureProfile.STABLE || q.stableForMs>=180))
        if(eligible && opportunityCapturedAt==null)opportunityCapturedAt=trace.capturedAtElapsedNanos
        val turn=AnalysisTurn(trace.traceId,trace.traceId,trace.frameId,gate.generation(),settings.mode,
            trace.capturedAtEpochMs,trace.capturedAtElapsedNanos,
            if(settings.useLocalOcr && settings.mode==AnalysisMode.TEXT_READING) "PP-OCRv5" else FrameTurnTransport.MODEL,
            FrameTurnTransport.PROMPT_VERSION,UUID.randomUUID().toString(),
            opportunityCapturedAtNanos=opportunityCapturedAt ?: trace.capturedAtElapsedNanos)
        val blackRatio=pixels.count { it<8 }.toDouble()/pixels.size
        DiagnosticHub.record("CANDIDATE_READY",turn.fields()+mapOf(
            "qualityProbeMs" to (SystemClock.elapsedRealtimeNanos()-qualityStarted)/1e6,
            "sharpness" to q.sharpness,"contrast" to q.contrast,"edgeDensityProxy" to q.textDensity,
            "cropCompletenessProxy" to q.cropCompleteness,"stableForMs" to q.stableForMs,"blackRatio" to blackRatio,
            "captureProfile" to settings.captureProfile.name,"sceneDescriptionStyle" to settings.sceneDescriptionStyle.name))
        FrameStages.record(trace.copy(turn=turn),"quality",qualityStarted)
        return Candidate(turn,trace.copy(turn=turn),settings,q,blackRatio,difference,target?.dissimilarity,target?.chromaDifference)
    }

    suspend fun process(bitmap:Bitmap,c:Candidate)=lane.withLock {
        val capture=c.turn;val settings=c.settings
        if(!runtime.analysing.value) { skip(c,"analysis_paused");return@withLock }
        if(capture.visualGeneration!=gate.generation()) { skip(c,"obsolete_candidate");return@withLock }
        if(c.blackRatio>=.94) {
            skip(c,"unavailable_image")
            imageUnavailable()
            return@withLock
        }
        availability.available()
        val textMode=settings.mode==AnalysisMode.TEXT_READING
        val requiredGrounding=GroundingPolicy.required(settings)
        // Configuration errors must not burn CPU encoding/OCR on every camera frame.
        val apiKey = if (AnalysisReadiness.requiresCloud(settings)) keys.get() else null
        if (AnalysisReadiness.error(settings, !apiKey.isNullOrBlank()) != null) {
            runtime.error(AnalysisReadiness.MISSING_KEY)
            DiagnosticHub.record("FRAME_CONFIGURATION_BLOCKED", capture.fields()+mapOf("reason" to "missing_api_key"))
            if (!configurationNoticeSpoken) {
                configurationNoticeSpoken = true
                tts.speakFeedback(AnalysisReadiness.MISSING_KEY)
            }
            return@withLock
        }
        configurationNoticeSpoken = false
        val decision=if(textMode) policy.consider(c.quality,SystemClock.elapsedRealtime(),settings.captureProfile==CaptureProfile.STABLE)
            else scenePolicy.shouldProbe(capture.visualGeneration,settings.sceneDescriptionStyle,SystemClock.elapsedRealtime(),
                c.compensatedDifference,c.chromaDifference).let { QualityRetryPolicy.Decision(it.accepted,it.reason) }
        if(!decision.submit) { skip(c,decision.reason);return@withLock }
        activeJob=currentCoroutineContext()[Job]
        if (!gate.withinGeneration(capture.visualGeneration) {
            policy.submitted(c.quality,SystemClock.elapsedRealtime());scenePolicy.submitted(SystemClock.elapsedRealtime());runtime.processing(true)
        }) { activeJob=null;return@withLock }
        fun activateAndBind(turn: AnalysisTurn): Boolean {
            if(!gate.activate(capture) { tts.invalidateVisualContent();runtime.clearVisualResult();runtime.processing(true) }) return false
            val accepted=gate.bindSubmission(turn)
            if(accepted) DiagnosticHub.record("TURN_ACTIVATED",turn.fields())
            return accepted
        }
        var failureStage = "encoding"
        var encodedHash: String? = null
        var bound:AnalysisTurn?=null;var acceptedReading="";var readingComplete=false;var spokenScene=""
        try {
            withContext(c.trace) {
                val encodingStarted=SystemClock.elapsedRealtimeNanos()
                val encoded=encoder.encode(bitmap,settings)
                encodedHash = encoded.imageHash
                FrameStages.record(c.trace,"encoding",encodingStarted,extra=mapOf("imageHash" to encoded.imageHash))
                currentCoroutineContext().ensureActive()
                // Only explicit strict trust / local reading owns mandatory optical evidence.
                // Advisory verification has a separate lifetime and cannot hold this scope open.
                failureStage = "local_grounding_or_submission"
                val evidenceTask=if(requiredGrounding) async(Dispatchers.Default) {
                    val groundingStarted=SystemClock.elapsedRealtimeNanos()
                    local.ensureLoaded().getOrThrow()
                    val exact=checkNotNull(BitmapFactory.decodeByteArray(encoded.bytes,0,encoded.bytes.size))
                    val result=try { local.read(exact,LocalReadingQuality.MAXIMUM) } finally { exact.recycle() }
                    FrameStages.record(c.trace,"localGrounding",groundingStarted,extra=mapOf("imageHash" to encoded.imageHash))
                    DiagnosticHub.record("LOCAL_GROUNDING_COMPLETED",capture.fields()+mapOf(
                        "durationMs" to (SystemClock.elapsedRealtimeNanos()-groundingStarted)/1e6,
                        "detectedBoxes" to result.detectedBoxCount,"localConfidence" to result.confidence,
                        "imageHash" to encoded.imageHash))
                    TextGroundingGate.Evidence(result.text,result.confidence,result.detectedBoxCount)
                } else null
                val preflight=if(requiredGrounding && (settings.useLocalOcr || acceptedOpticalText.isNotEmpty())) evidenceTask?.await() else null
                if(textMode && preflight!=null && preflight.confidence>=.80f && preflight.text.isNotBlank() &&
                    TextGroundingGate.canonical(preflight.text)==acceptedOpticalText) {
                    skip(c,"optically_verified_duplicate")
                    return@withContext
                }
                suspend fun accept(o:FrameTurnTransport.Output) {
                    if(gate.rejection(o.turn)!=null) { DiagnosticHub.record("RESULT_DROPPED",o.turn.fields()+mapOf("reason" to gate.rejection(o.turn)));return }
                    if(textMode) {
                        val verificationWaitStarted=SystemClock.elapsedRealtimeNanos()
                        if(requiredGrounding) DiagnosticHub.record("OUTPUT_VERIFICATION_WAIT_STARTED",o.turn.fields()+mapOf(
                            "evidenceAlreadyComplete" to evidenceTask?.isCompleted,
                            "readingComplete" to o.readingComplete))
                        val evidence=evidenceTask?.await()
                        if(requiredGrounding) DiagnosticHub.record("OUTPUT_VERIFICATION_READY",o.turn.fields()+mapOf(
                            "verificationWaitMs" to (SystemClock.elapsedRealtimeNanos()-verificationWaitStarted)/1e6))
                        // The target may have changed while optical evidence was being computed.
                        val rejection=gate.rejection(o.turn)
                        if(rejection!=null) {
                            DiagnosticHub.record("RESULT_DROPPED",o.turn.fields()+mapOf("reason" to rejection))
                            return
                        }
                        val currentText=o.text.trimEnd()
                        if(readingComplete && currentText==acceptedReading)return
                        if(!currentText.startsWith(acceptedReading)) {
                            DiagnosticHub.record("RESULT_DROPPED",o.turn.fields()+mapOf("reason" to "reading_prefix_rewritten"));return
                        }
                        val d=if(requiredGrounding) TextGroundingGate.evaluate(currentText,o.confidence,o.legible,o.inferred,checkNotNull(evidence))
                            else TextGroundingGate.modelOnly(currentText,o.confidence,o.legible,o.inferred)
                        DiagnosticHub.record("TEXT_GROUNDING_DECISION",o.turn.fields()+mapOf("accepted" to d.accepted,"retry" to d.retry,"reason" to d.reason))
                        if(!d.accepted) { gate.commit(o.turn) { policy.result(false);runtime.notice("النص غير واضح؛ وجّه الكاميرا بثبات") };return }
                        gate.commit(o.turn) {
                            val delta=currentText.removePrefix(acceptedReading).trim()
                            acceptedReading=currentText;readingComplete=o.readingComplete
                            if(readingComplete) { policy.result(true);acceptedOpticalText=evidence?.let { TextGroundingGate.canonical(it.text) }.orEmpty() }
                            val r=AnalysisResult(currentText,if(settings.useLocalOcr)AnalysisSource.LOCAL_OCR else AnalysisSource.GEMINI,turn=o.turn)
                            if(runtime.result(r)&&settings.speechEnabled&&delta.isNotEmpty())
                                tts.speakTurn(r,settings.speechRate,"READ_TEXT",spokenText=delta)
                        }
                    } else {
                        gate.commit(o.turn) {
                            val d=scenePolicy.output(o.text,o.confidence,o.legible,o.inferred,settings.sceneDescriptionStyle,spokenScene)
                            DiagnosticHub.record("SCENE_OUTPUT_DECISION",o.turn.fields()+mapOf("accepted" to d.accepted,"reason" to d.reason,
                                "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name))
                            if(!d.accepted)return@commit
                            val r=AnalysisResult(o.text,AnalysisSource.GEMINI,turn=o.turn)
                            if(runtime.result(r)&&settings.speechEnabled) {
                                tts.speakTurn(r,settings.speechRate,"SCENE_DESCRIPTION",spokenText=d.delta)
                            }
                            spokenScene=o.text
                        }
                    }
                }
                if(settings.useLocalOcr&&textMode) {
                    val evidence=checkNotNull(evidenceTask).await()
                    val turn=capture.copy(submittedAtNanos=SystemClock.elapsedRealtimeNanos(),imageHash=encoded.imageHash)
                    if(!activateAndBind(turn))return@withContext
                    bound=turn;DiagnosticHub.record("LOCAL_FRAME_BOUND",turn.fields()+mapOf(
                        "captureProfile" to settings.captureProfile.name,
                        "sceneDescriptionStyle" to settings.sceneDescriptionStyle.name,
                        "outputWidth" to encoded.width,"outputHeight" to encoded.height,
                        "encodedBytes" to encoded.bytes.size,"quality" to encoded.quality))
                    accept(FrameTurnTransport.Output(turn,evidence.text,"",(evidence.confidence*100).toInt(),true,false))
                } else {
                    val key = requireNotNull(apiKey)
                    failureStage = "cloud_transport_or_optical_verification"
                    val output=consumeLatestTurnOutput(
                        produce={ emit ->
                            // emit cannot suspend. Waiting for PP-OCR must never stop SSE draining
                            // or spend the network timeout on local inference.
                            transport.analyze(capture,encoded,settings,key,onSubmitted={
                                if(activateAndBind(it)) {
                                    bound=it
                                    if(GroundingPolicy.advisory(settings)) startAdvisory(it,encoded,c.trace)
                                    true
                                } else false
                            },onPartial=emit).also {
                                DiagnosticHub.record("TRANSPORT_RESPONSE_COMPLETED",it.turn.fields())
                            }
                        },
                        consume={ accept(it) },
                    )
                    if(textMode&&readingComplete&&acceptedReading==output.text.trimEnd()&&output.tail.isNotBlank()&&output.tail.split(Regex("\\s+")).size<=28) {
                        gate.commit(output.turn) {
                            val r=AnalysisResult(output.text,AnalysisSource.GEMINI,sceneTail=output.tail,turn=output.turn)
                            runtime.result(r)
                            if(settings.speechEnabled)tts.speakTurn(r,settings.speechRate,"SCENE_TAIL",spokenText=output.tail)
                        }
                    }
                }
            }
        } catch(e:CancellationException) {
            val expected=capture.visualGeneration!=gate.generation() || !runtime.analysing.value
            DiagnosticHub.record(if(expected) "TURN_CANCELLED_OBSOLETE" else "TURN_CANCELLED_UNEXPECTED",
                (bound?:capture).fields()+mapOf("expected" to expected))
            throw e
        } catch(e:Exception) {
            val turn=bound
            if(turn!=null)gate.commit(turn) { runtime.error("تعذر تحليل الصورة الحالية") }
            else gate.withinGeneration(capture.visualGeneration) { runtime.error("تعذر تجهيز الصورة للتحليل") }
            DiagnosticHub.record("FRAME_ANALYSIS_FAILURE",(bound?:capture).fields()+mapOf("errorType" to e.javaClass.simpleName,
                "failureStage" to failureStage, "encodedImageHash" to encodedHash,
                "codeLocation" to e.stackTrace.firstOrNull { it.className.startsWith("com.abdullah.visionbridge") }?.let { "${it.className}:${it.lineNumber}" }))
        } finally {
            gate.withinGeneration(capture.visualGeneration) { runtime.processing(false) }
            activeJob=null
            DiagnosticHub.record("ANALYSIS_LANE_RELEASED",(bound?:capture).fields()+mapOf("optionalGroundingBlocksLane" to false))
        }
    }

    private fun startAdvisory(turn:AnalysisTurn, image:LiveFrameEncoder.EncodedFrame, trace:DiagnosticTrace) {
        advisoryLane.submit(onEvent={ status ->
            DiagnosticHub.record("OPTIONAL_GROUNDING_${status.uppercase()}",turn.fields()+mapOf(
                "deadlineMs" to 1200,"blockingOutput" to false,"detectorLongEdge" to 640,"cropLimit" to 2))
        }) {
            withContext(trace.copy(turn=turn)) {
                advisoryLocal.ensureLoaded().getOrThrow()
                currentCoroutineContext().ensureActive()
                val decoded=checkNotNull(BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size))
                val scale=(640.0/maxOf(decoded.width,decoded.height)).coerceAtMost(1.0)
                val small=if(scale<1) Bitmap.createScaledBitmap(decoded,(decoded.width*scale).toInt().coerceAtLeast(1),
                    (decoded.height*scale).toInt().coerceAtLeast(1),true) else decoded
                try {
                    val evidence=advisoryLocal.read(small,LocalReadingQuality.FAST)
                    currentCoroutineContext().ensureActive()
                    DiagnosticHub.record("OPTIONAL_GROUNDING_EVIDENCE",turn.fields()+mapOf(
                        "detectedBoxes" to evidence.detectedBoxCount,"localConfidence" to evidence.confidence,
                        "currentTurn" to (gate.rejection(turn)==null),"blockingOutput" to false))
                } finally { if(small!==decoded)small.recycle();decoded.recycle() }
            }
        }
    }

    fun onVisualTargetChanged(interruptSpeech:Boolean) {
        advisoryLane.cancel()
        gate.invalidate { runtime.clearVisualResult();tts.invalidateVisualContent();policy.reset();scenePolicy.reset();acceptedOpticalText="" }
        activeJob?.cancel()
        synchronized(this) { previous=null;stableSince=0;opportunityCapturedAt=null }
        DiagnosticHub.record("VISUAL_GENERATION_CHANGED",mapOf("visualGeneration" to gate.generation(),"interruptPreference" to interruptSpeech))
    }
    suspend fun speakNotice(message:String)=tts.speakFeedback(message)
    fun imageUnavailable(message:String="الصورة غير متاحة") {
        if(!availability.missing())return
        onVisualTargetChanged(true)
        runtime.notice(message);tts.speakUrgentNotice(message)
        DiagnosticHub.record("IMAGE_UNAVAILABLE_ANNOUNCED",mapOf("visualGeneration" to gate.generation()))
    }
    fun stopSpeech()=onVisualTargetChanged(true)
    fun reset() { advisoryLane.resetSession();onVisualTargetChanged(true) }
    private fun skip(c:Candidate,reason:String) {
        DiagnosticHub.record("FRAME_SKIPPED",c.turn.fields()+mapOf("reason" to reason))
        if(reason.contains("quality")||reason=="awaiting_stability")DiagnosticHub.record("FRAME_SUPPRESSED_QUALITY",c.turn.fields())
        if(reason.contains("duplicate"))DiagnosticHub.record("FRAME_SUPPRESSED_DUPLICATE",c.turn.fields())
    }
}
