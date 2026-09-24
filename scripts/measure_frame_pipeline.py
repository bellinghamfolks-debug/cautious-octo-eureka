#!/usr/bin/env python3
"""Local, image-free aggregate diagnostics. Does not infer missing frame/result identity.
Never prints text, image paths, raw events, or session/trace identifiers. Input remains local.
"""
import argparse
from collections import Counter, defaultdict
import json
import math
import statistics
from pathlib import Path


def distribution(values):
    values = sorted(float(v) for v in values if isinstance(v, (float, int)) and math.isfinite(v) and v >= 0)
    def q(p):
        return values[max(0, math.ceil(len(values) * p) - 1)] if values else None
    return dict(n=len(values), median=statistics.median(values) if values else None, p90=q(.9), p95=q(.95), maximum=q(1))


def intervals(events, types):
    sessions = defaultdict(list)
    for e in events:
        if e.get('type') in types and isinstance(e.get('elapsedRealtimeNanos'), (int, float)):
            sessions[(e.get('sessionId'), e.get('processId'))].append(e['elapsedRealtimeNanos'])
    return distribution((b-a)/1e6 for times in sessions.values()
                        for a, b in zip(sorted(times), sorted(times)[1:]))


def response_health(events):
    """Join explicit turn IDs only. Report symptoms, never infer OCR correctness or causes."""
    turns = defaultdict(list)
    for event in events:
        if event.get('turnId'):
            turns[(event.get('sessionId'), event.get('processId'), event['turnId'])].append(event)
    totals = Counter()
    first_chunks = []
    for entries in turns.values():
        sent = next((e for e in entries if e.get('type') == 'FRAME_REQUEST_SENT'), None)
        if not sent:
            continue
        totals['explicitSubmittedTurns'] += 1
        types = {e.get('type') for e in entries}
        chunk = next((e for e in entries if e.get('type') == 'FIRST_CHUNK'), None)
        outputs = [e for e in entries if e.get('type') in {'RUNTIME_RESULT', 'TEXT_DISPLAYED'}]
        if chunk:
            captured = sent.get('capturedAtElapsedNanos')
            received = chunk.get('receivedAtElapsedNanos')
            if isinstance(captured, (int, float)) and isinstance(received, (int, float)):
                first_chunks.append((received-captured)/1e6)
            if not outputs:
                totals['responsesWithoutRuntimeOrUiOutput'] += 1
            if ('CLOUD_ANALYSIS_BUDGET_EXCEEDED' in types and
                    'LOCAL_GROUNDING_COMPLETED' not in types):
                totals['responseThenTimeoutWithoutCompletedGrounding'] += 1
        for output in outputs:
            identity = ('traceId', 'frameId', 'visualGeneration', 'mode', 'model',
                        'transportSessionId', 'imageHash')
            if any(output.get(key) is None or sent.get(key) is None for key in identity):
                totals['outputsWithIncompleteIdentity'] += 1
            elif any(output[key] != sent[key] for key in identity):
                totals['outputsWithMismatchedIdentity'] += 1
    return {
        **{key: totals[key] for key in (
            'explicitSubmittedTurns', 'responsesWithoutRuntimeOrUiOutput',
            'responseThenTimeoutWithoutCompletedGrounding', 'outputsWithIncompleteIdentity',
            'outputsWithMismatchedIdentity')},
        'captureToFirstChunkMs_notUsefulOutput': distribution(first_chunks),
        'interpretation': 'Observed symptoms only. A chunk is not verified useful output; absent events do not establish the cause.',
    }


def summarize(events):
    counts = Counter(e.get('type') for e in events)
    field = lambda types, name: distribution(e.get(name) for e in events if e.get('type') in types)
    skipped = Counter(str(e.get('reason', e.get('decisionReason', 'UNSPECIFIED')))
                      for e in events if e.get('type') in {'FRAME_DROPPED', 'FRAME_SKIPPED'})
    return {
        'kind': 'aggregate_only_no_identity_inference',
        'counterSemantics': 'Observed event counts only; zero for an uninstrumented event is not proof it never happened.',
        'counts': {
            'acquiredFrames': counts['FRAME_ACQUIRED'],
            'selectedFrames': counts['FRAME_SELECTED_FOR_ANALYSIS'],
            'submittedFrames': counts['LIVE_FRAME_SENT'] + counts['FRAME_REQUEST_SENT'],
            'completedTurns': counts['LIVE_TURN_COMPLETE'] + counts['TURN_COMPLETE'],
            'cancelledAsObsolete': counts['TURN_CANCELLED_OBSOLETE'],
            'suppressedByQuality': counts['FRAME_SUPPRESSED_QUALITY'],
            'suppressedAsDuplicate': counts['FRAME_SUPPRESSED_DUPLICATE'],
            'speechBackpressure': counts['LIVE_LOCAL_SPEECH_BACKPRESSURE'],
            'viewportFallback': counts['ESIGHT_VIEWPORT_FALLBACK_AUTO'],
            'runtimeResults': counts['RUNTIME_RESULT'],
            'displayedResults': counts['TEXT_DISPLAYED'],
        },
        'skipReasons': dict(skipped),
        'responseHealth': response_health(events),
        'interSubmissionMs': intervals(events, {'LIVE_FRAME_SENT', 'FRAME_REQUEST_SENT'}),
        'interCompletionMs': intervals(events, {'LIVE_TURN_COMPLETE', 'TURN_COMPLETE'}),
        'legacyTtsQueueWaitMs': field({'TTS_UTTERANCE_STARTED'}, 'queueWaitMs'),
        'ttsQueueAgeMs': field({'TTS_UTTERANCE_STARTED'}, 'queueAgeMs'),
        'captureConversionMs': field({'FRAME_ACQUIRED'}, 'conversionMs'),
        'trackingMs': field({'SMART_TARGET_POLICY_DECISION'}, 'trackingMs'),
        'jpegCompressionMs': field({'LIVE_FRAME_SENT', 'FRAME_REQUEST_SENT'}, 'compressionMs'),
        'base64Ms': field({'LIVE_FRAME_SENT', 'FRAME_REQUEST_SENT'}, 'base64Ms'),
        'opticalVerificationWaitMs': field({'OUTPUT_VERIFICATION_READY'}, 'verificationWaitMs'),
        'localGroundingMs': field({'LOCAL_GROUNDING_COMPLETED'}, 'durationMs'),
        'reportedLegacyEncodeTotalMs_notPureEncoding': field({'LIVE_FRAME_SENT'}, 'encodeTotalMs'),
        'evidenceWriteMs': field({'EVIDENCE_FRAME_CAPTURED'}, 'evidenceWriteMs'),
        'usefulEndToEnd': {
            'status': 'NOT_MEASURED',
            'reason': 'Requires annotated opportunities and exact accepted frame/turn/UI/TTS binding; inter-event gaps are not end-to-end latency.',
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events', type=Path, help='One session events.jsonl, local only')
    parser.add_argument('--output', type=Path, required=True, help='Local aggregate JSON destination')
    args = parser.parse_args()
    events = [json.loads(line) for line in args.events.read_text().splitlines() if line.strip()]
    args.output.write_text(json.dumps(summarize(events), indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
