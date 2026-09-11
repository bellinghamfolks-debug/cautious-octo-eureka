import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('measure', Path(__file__).parents[1] / 'measure_frame_pipeline.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MeasurementsTest(unittest.TestCase):
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
