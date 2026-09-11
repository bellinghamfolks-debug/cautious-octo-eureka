#!/usr/bin/env python3
"""Evaluate local annotated replay JSON. Never treats build success as a latency pass.
Schema is documented in docs/FRAME_PERFORMANCE_ACCEPTANCE.md. Outputs aggregates only.
"""
import argparse
import json
import math
from pathlib import Path
from measure_frame_pipeline import distribution

LIMITS = {'TEXT_READING/FAST': (2000, 3000), 'TEXT_READING/STABLE': (3000, 4500),
          'SCENE_DESCRIPTION/BRIEF': (2000, 3000), 'SCENE_DESCRIPTION/COMPREHENSIVE': (None, None)}
IDENTITY = ('turnId', 'traceId', 'frameId', 'visualGeneration', 'mode', 'model',
            'promptVersion', 'transportSessionId', 'imageHash')
STAGES = ('capture', 'tracking', 'preprocessing', 'encoding', 'localGrounding',
          'networkSetup', 'networkModel', 'runtimeAcceptance', 'uiRender', 'ttsQueue', 'ttsEngineStart')


def covered_ms(spans):
    """Union of monotonic intervals, for overlapping preprocessing/encoding work."""
    end = None
    total = 0
    for start, stop in sorted(spans):
        if stop < start:
            raise ValueError('Negative stage interval')
        total += max(0, stop - max(start, end if end is not None else start))
        end = max(stop, end if end is not None else stop)
    return total / 1e6


def complete_percentiles(values):
    """Missing opportunity latency counts as infinity, rather than vanishing from percentiles."""
    ranked = sorted(float('inf') if v is None else v for v in values)
    def q(p):
        value = ranked[max(0, math.ceil(len(ranked)*p)-1)] if ranked else float('inf')
        return value if math.isfinite(value) else None
    median = ((ranked[(len(ranked)-1)//2] + ranked[len(ranked)//2])/2) if ranked else float('inf')
    return {'median': median if math.isfinite(median) else None, 'p90': q(.9), 'p95': q(.95)}


def evaluate(data):
    out = {}
    all_opportunities = data.get('opportunities', [])
    for mode, (median_limit, p90_limit) in LIMITS.items():
        rows = [o for o in all_opportunities if o.get('workload') == mode]
        latencies, queues, combined, invalid, missing_stages = [], [], [], 0, 0
        durations = {s: [] for s in STAGES}
        normal = []
        for row in rows:
            identity = row.get('submitted', {})
            output = row.get('output', {})
            endpoints = row.get('timesNanos', {})
            speech = row.get('speechEnabled', True)
            required_endpoints = ('capturedAt', 'runtimeAcceptedAt', 'uiRenderedAt') + (
                ('ttsEligibleAt', 'ttsStartedAt') if speech else ())
            useful = (row.get('groundTruthMatch') is True and output.get('accepted') is True
                      and output.get('useful') is True and output.get('obsolete') is False)
            bound = (all(identity.get(k) is not None and identity.get(k) == output.get(k) for k in IDENTITY)
                     and identity.get('mode') == mode.split('/')[0]
                     and isinstance(identity.get('imageHash'), str) and len(identity['imageHash']) == 64)
            times_valid = all(isinstance(endpoints.get(k), int) for k in required_endpoints)
            target_start = row.get('opportunityCapturedAtNanos')
            times_valid = times_valid and isinstance(target_start, int)
            if times_valid:
                c = endpoints['capturedAt']; r = endpoints['runtimeAcceptedAt']
                times_valid = target_start <= c <= r <= endpoints['uiRenderedAt']
                if speech:
                    times_valid = times_valid and r <= endpoints['ttsEligibleAt'] <= endpoints['ttsStartedAt']
            valid = useful and bound and times_valid
            invalid += int(not valid)
            end = endpoints.get('ttsStartedAt' if speech else 'uiRenderedAt')
            latency = (end-target_start)/1e6 if valid else None
            latencies.append(latency)
            if row.get('normalCondition') is True:
                normal.append(latency)
            if valid and speech:
                queues.append((endpoints['ttsStartedAt']-endpoints['ttsEligibleAt'])/1e6)
            stages = row.get('stagesNanos', {})
            for stage in STAGES:
                spans = stages.get(stage)
                if spans is None:
                    missing_stages += 1
                else:
                    durations[stage].append(covered_ms(spans))
            if 'preprocessing' in stages and 'encoding' in stages:
                combined.append(covered_ms(stages['preprocessing']+stages['encoding']))
        percentiles = complete_percentiles(latencies)
        failures = []
        if len(rows) < 30: failures.append('insufficient_annotated_opportunities')
        if invalid: failures.append('missing_incorrect_obsolete_or_unbound_outputs')
        if missing_stages: failures.append('missing_stage_measurements')
        if mode.endswith('COMPREHENSIVE'):
            if not normal or any(x is None or x > 3000 for x in normal):
                failures.append('normal_first_important_clause_deadline')
        else:
            for name, limit in [('median', median_limit), ('p90', p90_limit)]:
                if percentiles[name] is None or percentiles[name] > limit:
                    failures.append(name+'_useful_deadline')
        enc = distribution(combined); queue = distribution(queues)
        if enc['n'] != len(rows) or enc['p95'] is None or enc['p95'] >= 400:
            failures.append('preprocessing_encoding_budget')
        speech_rows = sum(row.get('speechEnabled', True) for row in rows)
        if speech_rows and (queue['n'] != speech_rows or queue['p90'] > 250 or queue['maximum'] > 1000):
            failures.append('first_useful_tts_queue_budget')
        out[mode] = dict(status='FAIL' if failures else 'PASS', opportunities=len(rows),
                         usefulOutputs=len(rows)-invalid, missedOrInvalid=invalid,
                         captureToUsefulMs=percentiles, firstTtsQueueAgeMs=queue,
                         preprocessingEncodingMs=enc, stages={s:distribution(v) for s,v in durations.items()},
                         failures=failures)
    return {'scope':'annotated_replay_latency_only_not_release_approval', 'workloads':out}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('replay', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(evaluate(json.loads(args.replay.read_text())), indent=2)+'\n')
