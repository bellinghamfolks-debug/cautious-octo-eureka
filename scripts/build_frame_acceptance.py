#!/usr/bin/env python3
"""Join local diagnostic events to independently annotated useful revisions, never by proximity.
No images, text, network calls or model confidence are used as ground truth.
"""
import argparse
import json
import re
from pathlib import Path
from evaluate_frame_acceptance import IDENTITY, STAGES
from replay_diagnostic_bundle import require_private_path


def build(events, annotations):
    rows = []
    for annotation in annotations['opportunities']:
        row = {k: annotation.get(k) for k in ('workload', 'speechEnabled', 'normalCondition',
               'opportunityCapturedAtNanos', 'groundTruthMatch')}
        row['speechEnabled'] = annotation.get('speechEnabled', True)
        row.update(submitted={}, output={}, timesNanos={}, stagesNanos={})
        rows.append(row)  # Missing submissions/results remain in the denominator.
        scope = ('sessionId', 'processId')
        if any(annotation.get(k) is None for k in (*scope, 'turnId')):
            row['bindingFailure'] = 'missing_annotation_identity'
            continue
        session = [e for e in events if all(e.get(k) == annotation[k] for k in scope)]
        owned = [e for e in session if e.get('turnId') == annotation['turnId']]
        sent = [e for e in owned if e.get('type') in ('FRAME_REQUEST_SENT', 'LOCAL_FRAME_BOUND')]
        if len(sent) != 1:
            row['bindingFailure'] = 'missing_or_ambiguous_submission'
            continue
        owner = sent[0]
        style = owner.get('captureProfile') if owner.get('mode') == 'TEXT_READING' else owner.get('sceneDescriptionStyle')
        if annotation.get('workload') != f"{owner.get('mode')}/{style}":
            row['bindingFailure'] = 'workload_does_not_match_submission'
            continue
        row['submitted'] = {k: owner.get(k) for k in IDENTITY}
        row['timesNanos']['capturedAt'] = owner.get('capturedAtElapsedNanos')
        revision = annotation.get('acceptedContentHash', '')
        if not isinstance(revision, str) or not re.fullmatch('[0-9a-f]{64}', revision):
            row['bindingFailure'] = 'missing_annotated_useful_revision'
            continue
        def bound(e):
            return all(owner.get(k) is not None and e.get(k) == owner[k] for k in IDENTITY)
        revisions = [e for e in owned if bound(e) and e.get('acceptedContentHash') == revision]
        accepted = [e for e in revisions if e.get('type') == 'RUNTIME_RESULT']
        if len(accepted) != 1:
            row['bindingFailure'] = 'missing_or_ambiguous_runtime_revision'
            continue
        runtime = accepted[0]
        display = next((e for e in revisions if e.get('type') == 'TEXT_DISPLAYED'), None)
        speech = next((e for e in revisions if e.get('type') == 'TTS_UTTERANCE_STARTED'
                       and e.get('contentSection') != 'SCENE_TAIL'), None)
        outputs = [e for e in (runtime, display, speech) if e is not None]
        active = None
        generation = None
        obsolete = False
        for e in session:
            if e.get('type') == 'VISUAL_GENERATION_CHANGED':
                generation = e.get('visualGeneration'); active = None
            if e.get('type') == 'TURN_ACTIVATED':
                active = e.get('turnId'); generation = e.get('visualGeneration')
            if any(e is output for output in outputs):
                obsolete |= (active != owner['turnId'] or generation != owner['visualGeneration'])
        row['output'] = dict(row['submitted'], accepted=True,
                             useful=annotation.get('useful') is True, obsolete=obsolete,
                             acceptedContentHash=revision)
        times = row['timesNanos']
        times['runtimeAcceptedAt'] = runtime.get('acceptedAtElapsedNanos')
        if display:
            times['uiRenderedAt'] = display.get('displayedAtElapsedNanos')
        if speech:
            times['ttsEligibleAt'] = speech.get('ttsEligibleAtElapsedNanos')
            times['ttsStartedAt'] = speech.get('ttsStartedAtElapsedNanos')
        for e in owned:
            if e.get('type') != 'FRAME_STAGE' or e.get('stage') not in STAGES:
                continue
            # Before encoding, the capture trace has no wire hash yet. Require both exact
            # capture IDs; never borrow a stage from a nearby frame or a later accepted prefix.
            if any(e.get(k) != owner.get(k) for k in ('traceId', 'frameId')):
                continue
            if e.get('imageHash') not in (None, owner.get('imageHash')):
                continue
            if e.get('acceptedContentHash') not in (None, revision):
                continue
            if e.get('stage') in ('ttsQueue', 'ttsEngineStart') and (
                    speech is None or e.get('utteranceId') != speech.get('utteranceId')):
                continue
            start, end = e.get('stageStartedAtElapsedNanos'), e.get('stageEndedAtElapsedNanos')
            if isinstance(start, int) and isinstance(end, int) and 0 <= start <= end:
                row['stagesNanos'].setdefault(e['stage'], []).append([start, end])
        # Explicitly inapplicable phases, not fabricated zero-duration measurements.
        inapplicable = set()
        if owner.get('mode') == 'SCENE_DESCRIPTION':
            inapplicable.add('localGrounding')
        if owner.get('type') == 'LOCAL_FRAME_BOUND':
            inapplicable.update(('networkSetup', 'networkModel', 'requestEncoding'))
        if not row['speechEnabled']:
            inapplicable.update(('ttsQueue', 'ttsEngineStart'))
        for stage in inapplicable:
            row['stagesNanos'].setdefault(stage, [])
        row['inapplicableStages'] = sorted(inapplicable)
    return {'scope': 'explicit_identity_join_requires_independent_annotations', 'opportunities': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events', type=Path)
    parser.add_argument('annotations', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    events = [json.loads(line) for line in require_private_path(args.events).read_text().splitlines() if line.strip()]
    annotations = json.loads(require_private_path(args.annotations).read_text())
    require_private_path(args.output).write_text(json.dumps(build(events, annotations), indent=2)+'\n')
