import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('measure', Path(__file__).parents[1] / 'measure_frame_pipeline.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MeasurementsTest(unittest.TestCase):
    def test_response_timeout_without_grounding_is_reported_not_called_success(self):
        events = [dict(type=kind, turnId='synthetic', sessionId='s', processId='p',
                       capturedAtElapsedNanos=0, receivedAtElapsedNanos=13_000_000_000)
                  for kind in ('FRAME_REQUEST_SENT', 'FIRST_CHUNK', 'CLOUD_ANALYSIS_BUDGET_EXCEEDED')]
        r = module.summarize(events)
        self.assertEqual(1, r['responseHealth']['responseThenTimeoutWithoutCompletedGrounding'])
        self.assertEqual(1, r['responseHealth']['responsesWithoutRuntimeOrUiOutput'])
        self.assertEqual(13000, r['responseHealth']['captureToFirstChunkMs_notUsefulOutput']['median'])
        self.assertEqual('NOT_MEASURED', r['usefulEndToEnd']['status'])

    def test_health_does_not_join_turns_across_sessions(self):
        events = [dict(type='FRAME_REQUEST_SENT', turnId='same', sessionId='old'),
                  dict(type='FIRST_CHUNK', turnId='same', sessionId='new'),
                  dict(type='CLOUD_ANALYSIS_BUDGET_EXCEEDED', turnId='same', sessionId='new')]
        r = module.response_health(events)
        self.assertEqual(0, r['responseThenTimeoutWithoutCompletedGrounding'])

    def test_health_flags_mismatched_and_incomplete_output_identity(self):
        identity = dict(turnId='t', traceId='trace', frameId='frame', visualGeneration=0,
                        mode='TEXT_READING', model='synthetic', transportSessionId='transport', imageHash='a'*64)
        events = [dict(identity, type='FRAME_REQUEST_SENT'),
                  dict(identity, type='RUNTIME_RESULT', imageHash='b'*64),
                  dict(type='TEXT_DISPLAYED', turnId='t')]
        r = module.response_health(events)
        self.assertEqual(1, r['outputsWithMismatchedIdentity'])
        self.assertEqual(1, r['outputsWithIncompleteIdentity'])

    def test_missing_latency_is_not_zero_or_pass(self):
        report = module.summarize([{'type': 'LIVE_TURN_COMPLETE'}])
        self.assertIsNone(report['ttsQueueAgeMs']['p90'])
        self.assertEqual('NOT_MEASURED', report['usefulEndToEnd']['status'])

    def test_intervals_do_not_cross_sessions_or_reboots(self):
        events = [dict(type='LIVE_FRAME_SENT', sessionId=s, processId=p, elapsedRealtimeNanos=t)
                  for s, p, t in [('a','1',0), ('a','1',7_000_000_000), ('b','1',100), ('a','2',200)]]
        self.assertEqual({'n':1, 'median':7000, 'p90':7000, 'p95':7000, 'maximum':7000},
                         module.intervals(events, {'LIVE_FRAME_SENT'}))

    def test_legacy_queue_is_not_relabelled_new_queue(self):
        r = module.summarize([dict(type='TTS_UTTERANCE_STARTED', queueWaitMs=19000)])
        self.assertEqual(19000, r['legacyTtsQueueWaitMs']['maximum'])
        self.assertEqual(0, r['ttsQueueAgeMs']['n'])

    def test_output_does_not_copy_private_content(self):
        report = str(module.summarize([dict(type='LIVE_FRAME_SENT', text='PRIVATE', frameId='PRIVATE', file='PRIVATE')]))
        self.assertNotIn('PRIVATE', report)

    def test_nearest_rank_percentiles_and_invalid_samples(self):
        self.assertEqual(90, module.distribution(list(range(1,101))+[None,-1,float('nan')])['p90'])

import sys
sys.path.insert(0, str(Path(__file__).parents[1]))
import evaluate_frame_acceptance as acceptance


class AcceptanceTest(unittest.TestCase):
    def valid_row(self):
        identity={key: 'synthetic' for key in acceptance.IDENTITY}
        identity.update(mode='TEXT_READING', imageHash='a'*64, visualGeneration=0)
        return dict(workload='TEXT_READING/FAST', groundTruthMatch=True, submitted=identity,
                    output=dict(identity, accepted=True, useful=True, obsolete=False),
                    opportunityCapturedAtNanos=0,
                    timesNanos=dict(capturedAt=0, runtimeAcceptedAt=1_000_000_000,
                                    uiRenderedAt=1_100_000_000, ttsEligibleAt=1_000_000_000,
                                    ttsStartedAt=1_200_000_000),
                    stagesNanos={s: [[0, 1_000_000]] for s in acceptance.STAGES})

    def test_reports_ui_and_speech_separately(self):
        r=acceptance.evaluate({'opportunities':[self.valid_row()]*30})['workloads']['TEXT_READING/FAST']
        self.assertEqual('PASS',r['status'])
        self.assertEqual(1100,r['captureToUiMs']['median'])
        self.assertEqual(1200,r['captureToSpeechMs']['median'])

    def test_base64_and_payload_cost_cannot_hide_outside_encoding_budget(self):
        row=self.valid_row()
        row['stagesNanos']['requestEncoding']=[[1_000_000, 450_000_000]]
        r=acceptance.evaluate({'opportunities':[row]*30})['workloads']['TEXT_READING/FAST']
        self.assertIn('preprocessing_encoding_budget',r['failures'])
        self.assertEqual(450,r['preprocessingEncodingMs']['p95'])

    def test_64_characters_do_not_make_a_valid_image_digest(self):
        row=self.valid_row()
        row['submitted']['imageHash']=row['output']['imageHash']='z'*64
        r=acceptance.evaluate({'opportunities':[row]*30})['workloads']['TEXT_READING/FAST']
        self.assertEqual(30,r['missedOrInvalid'])

    def test_silence_remains_in_percentile_denominator(self):
        samples = [1000]*8 + [None]*2
        self.assertIsNone(acceptance.complete_percentiles(samples)['p90'])

    def test_parallel_stages_not_double_counted(self):
        self.assertEqual(300, acceptance.covered_ms([(0,200_000_000),(100_000_000,300_000_000)]))

    def test_empty_replay_never_passes(self):
        result = acceptance.evaluate({})
        self.assertTrue(all(r['status']=='FAIL' for r in result['workloads'].values()))

    def test_rejects_missing_identity_despite_fast_timestamps(self):
        row = dict(workload='TEXT_READING/FAST', groundTruthMatch=True,
                   output=dict(accepted=True,useful=True,obsolete=False),
                   opportunityCapturedAtNanos=0,
                   timesNanos=dict(capturedAt=0,runtimeAcceptedAt=1,uiRenderedAt=2,ttsEligibleAt=3,ttsStartedAt=4))
        r = acceptance.evaluate({'opportunities':[row]*30})['workloads']['TEXT_READING/FAST']
        self.assertEqual(30, r['missedOrInvalid'])
        self.assertEqual('FAIL', r['status'])
