#!/usr/bin/env python3
"""Local private-ledger reconstruction. Never uploads or extracts images or calls a model."""
import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import zipfile
from measure_frame_pipeline import summarize

REPOSITORY=Path(__file__).resolve().parents[1]

def require_private_path(path):
    path=Path(path).resolve()
    if path==REPOSITORY or REPOSITORY in path.parents:
        raise ValueError('Private input/output must remain outside the repository')
    return path

def rebuild(archive,session_hint=None):
    with zipfile.ZipFile(archive) as z:
        names=z.namelist()
        if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts for n in names):
            raise ValueError('Unsafe ZIP member')
        sessions={n:[json.loads(l) for l in z.read(n).splitlines() if l.strip()]
                  for n in names if n.endswith('/events.jsonl')}
        chosen=[n for n in sessions if session_hint and session_hint in n]
        if session_hint and len(chosen)!=1:raise ValueError('Session hint must identify one session')
        current=chosen[0] if chosen else max(sessions,key=lambda n:max((e.get('epochMs',0) for e in sessions[n]),default=0))
        frames=[];turns=[];speech=[]
        for session,events in sessions.items():
            grouped=defaultdict(list)
            for e in events:
                if e.get('frameId'):grouped[e['frameId']].append(e)
            rows={}
            for frame,es in grouped.items():
                images=[]
                for e in es:
                    filename=e.get('file')
                    if not filename or e.get('type')!='EVIDENCE_FRAME_CAPTURED':continue
                    matches=[n for n in names if n==filename or n=='evidence/'+filename]
                    if not matches:matches=[n for n in names if n.startswith('evidence/') and PurePosixPath(n).name==PurePosixPath(filename).name]
                    member=matches[0] if len(matches)==1 else None
                    images.append(dict(member=member,role=e.get('evidenceRole'),reason=e.get('reason'),
                        evidenceSha256=hashlib.sha256(z.read(member)).hexdigest() if member else None,isTransmittedImageHash=False))
                row=dict(session=session,frameId=frame,traceIds=sorted({e['traceId'] for e in es if e.get('traceId')}),
                    capturedAtEpochMs=next((e['capturedAtEpochMs'] for e in es if 'capturedAtEpochMs' in e),None),
                    capturedAtElapsedNanos=next((e['capturedAtElapsedNanos'] for e in es if 'capturedAtElapsedNanos' in e),None),
                    selected=any(e.get('type')=='FRAME_SELECTED_FOR_ANALYSIS' for e in es),images=images,events=es,turns=[],
                    wireFrameResultMatch='UNPROVABLE_UNLESS_EXPLICIT_SUBMITTED_IDENTITY_AND_HASH')
                rows[frame]=row;frames.append(row)
            dispatch=None
            for e in events:
                if e.get('type')=='ANALYSIS_DISPATCH_STARTED':dispatch=e.get('frameId')
                if e.get('type') in ('LIVE_FRAME_SENT','FRAME_REQUEST_SENT','LOCAL_FRAME_BOUND'):
                    explicit=e.get('frameId');frame=explicit or dispatch;epoch=e.get('epoch')
                    associated=[v for v in events if (e.get('turnId') and v.get('turnId')==e['turnId']) or
                        (not e.get('turnId') and epoch is not None and v.get('epoch')==epoch)]
                    turn=dict(session=session,turnId=e.get('turnId'),legacyEpoch=epoch,explicitFrameId=explicit,
                        inferredFrameId=None if explicit else frame,association='EXPLICIT' if explicit else 'DISPATCH_TIME_INFERENCE_NOT_WIRE_PROOF',
                        visualGeneration=e.get('visualGeneration'),sent=e,events=associated,encodedImageHash=e.get('imageHash'))
                    turns.append(turn)
                    if frame in rows:rows[frame]['turns'].append(turn)
                if e.get('type','').startswith('TTS_'):
                    speech.append(dict(session=session,event=e,association='EXPLICIT' if e.get('turnId') and e.get('frameId') else 'UNBOUND_DO_NOT_ASSIGN_BY_LAST_FRAME'))
        selected=[f for f in frames if f['session']==current and f['selected']]
        current_turns=[t for t in turns if t['session']==current]
        return dict(manifest=json.loads(z.read('export_manifest.json')) if 'export_manifest.json' in names else {},
            session=current,frames=frames,turns=turns,speech=speech,currentMetrics=summarize(sessions[current]),
            replaySequence=[dict(frameId=f['frameId'],traceIds=f['traceIds'],capturedAtElapsedNanos=f['capturedAtElapsedNanos'],
                analysisImages=[i for i in f['images'] if i.get('role')=='analysis_input' or str(i.get('reason','')).startswith('analysis_input')]) for f in selected],
            summary=dict(sessions=len(sessions),frameGroups=len(frames),selectedCurrent=len(selected),submittedCurrent=len(current_turns),
                explicitBoundCurrent=sum(bool(t['explicitFrameId'] and t['turnId'] and t['encodedImageHash']) for t in current_turns),currentSourceEvents=len(sessions[current])))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive',type=Path)
    p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--session');a=p.parse_args()
    archive=require_private_path(a.archive);out=require_private_path(a.output_dir)
    os.umask(0o077);out.mkdir(parents=True,exist_ok=True,mode=0o700)
    data=rebuild(archive,a.session);data['summary']['archiveSha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
    for key,value in data.items():(out/(key+'.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(data['summary']))  # counts/hash only, no image paths or user content

if __name__=='__main__':main()
