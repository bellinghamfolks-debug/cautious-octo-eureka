import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from build_frame_acceptance import build


class AcceptanceJoinTest(unittest.TestCase):
    def test_pre_submission_stages_join_by_exact_capture_not_a_different_turn(self):
        events, annotation=self.sample()
        stage=dict(sessionId='synthetic-session',processId='1',traceId='trace',frameId='frame',
            type='FRAME_STAGE',stage='tracking',stageStartedAtElapsedNanos=10,stageEndedAtElapsedNanos=12)
        events.extend([stage,dict(stage,traceId='another'),dict(stage,turnId='other-turn'),
                       dict(stage,stage='encoding')])
        row=build(events,{'opportunities':[annotation]})['opportunities'][0]
        self.assertEqual([[10,12]],row['stagesNanos']['tracking'])
        self.assertNotIn('encoding',row['stagesNanos'])

    def test_only_explicitly_optional_grounding_is_inapplicable_to_publication_latency(self):
        for required in (None,True,False):
            events, annotation=self.sample()
            events[1]['requiresLocalGrounding']=required
            row=build(events,{'opportunities':[annotation]})['opportunities'][0]
            self.assertEqual(required is False,'localGrounding' in row['inapplicableStages'])

    def sample(self):
        identity = dict(sessionId='synthetic-session', processId='1', turnId='turn', traceId='trace',
                        frameId='frame', visualGeneration=0, mode='TEXT_READING', model='synthetic',
                        promptVersion='test', transportSessionId='request', imageHash='a'*64,
                        captureProfile='FAST', capturedAtElapsedNanos=10)
        events = [dict(identity, type='TURN_ACTIVATED'), dict(identity, type='FRAME_REQUEST_SENT'),
                  dict(identity, type='RUNTIME_RESULT', acceptedContentHash='b'*64, acceptedAtElapsedNanos=20),
                  dict(identity, type='TEXT_DISPLAYED', acceptedContentHash='b'*64, displayedAtElapsedNanos=30),
                  dict(identity, type='TTS_UTTERANCE_STARTED', acceptedContentHash='b'*64,
                       ttsEligibleAtElapsedNanos=25, ttsStartedAtElapsedNanos=40,
                       utteranceId='speech', contentSection='READ_TEXT')]
        annotation = dict(sessionId='synthetic-session', processId='1', turnId='turn',
                          acceptedContentHash='b'*64, workload='TEXT_READING/FAST',
                          opportunityCapturedAtNanos=10, groundTruthMatch=True, useful=True)
        return events, annotation

    def test_exact_revision_links_endpoints_without_copying_text(self):
        events, annotation = self.sample()
        events[2]['readText'] = 'PRIVATE MUST NOT APPEAR'
        row = build(events, {'opportunities': [annotation]})['opportunities'][0]
        self.assertFalse(row['output']['obsolete'])
        self.assertEqual(40, row['timesNanos']['ttsStartedAt'])
        self.assertNotIn('PRIVATE', str(row))

    def test_other_process_cannot_supply_missing_speech(self):
        events, annotation = self.sample()
        events[-1]['processId'] = 'another-process'
        row = build(events, {'opportunities': [annotation]})['opportunities'][0]
        self.assertNotIn('ttsStartedAt', row['timesNanos'])

    def test_same_frame_wrong_revision_and_scene_tail_are_not_useful_reading(self):
        for field, value in [('acceptedContentHash', 'c'*64), ('contentSection', 'SCENE_TAIL')]:
            events, annotation = self.sample()
            events[-1][field] = value
            row = build(events, {'opportunities': [annotation]})['opportunities'][0]
            self.assertNotIn('ttsStartedAt', row['timesNanos'])

    def test_transition_before_speech_invalidates_the_opportunity(self):
        events, annotation = self.sample()
        events.insert(-1, dict(events[0], type='VISUAL_GENERATION_CHANGED', visualGeneration=1))
        row = build(events, {'opportunities': [annotation]})['opportunities'][0]
        self.assertTrue(row['output']['obsolete'])

    def test_missing_submission_is_retained_and_never_temporally_guessed(self):
        events, annotation = self.sample()
        events[1]['turnId'] = 'another-turn'
        result = build(events, {'opportunities': [annotation]})
        self.assertEqual(1, len(result['opportunities']))
        self.assertEqual('missing_or_ambiguous_submission', result['opportunities'][0]['bindingFailure'])

    def test_faster_profile_cannot_be_labelled_stable(self):
        events, annotation = self.sample()
        annotation['workload'] = 'TEXT_READING/STABLE'
        row = build(events, {'opportunities': [annotation]})['opportunities'][0]
        self.assertEqual('workload_does_not_match_submission', row['bindingFailure'])
