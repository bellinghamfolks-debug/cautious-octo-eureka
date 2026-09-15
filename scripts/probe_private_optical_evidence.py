#!/usr/bin/env python3
"""Offline exploratory PP-OCR probe of existing evidence JPEGs, NOT Android/Gemini replay.
Uses pinned production weights, DB thresholds and normalization. OpenCV resizing, unmerged
regions and greedy CTC differ from Android's deskew/grouping/beam/bidi path. Results cannot
establish phone latency, production OCR recall, or a post-fix golden pass. Never uploads data.
"""
import argparse
import io
import json
import math
from pathlib import Path
import time
import zipfile
from fetch_ocr_models import MODELS, digest
from replay_diagnostic_bundle import require_private_path


def probe(archive, frames, models, output):
    import cv2
    import numpy as np
    import onnxruntime as ort
    from PIL import Image
    sessions = {}
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    for model in MODELS:
        path = models/model.local_name
        if digest(path) != model.sha256:
            raise ValueError('Model checksum mismatch')
        sessions[model.local_name] = ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])
    def run(session, tensor):
        return session.run(None, {session.get_inputs()[0].name: tensor})[0]
    def tensor(rgb, detection=False):
        arr = rgb.astype(np.float32)/255
        mean, std = ([.485,.456,.406],[.229,.224,.225]) if detection else ([.5]*3,[.5]*3)
        return np.ascontiguousarray(((arr-np.array(mean,dtype=np.float32))/np.array(std,dtype=np.float32)).transpose(2,0,1)[None])
    def recognize(crop, name):
        session = sessions[name]
        raw = session.get_modelmeta().custom_metadata_map['character']
        dictionary = raw.split('\n')
        if dictionary[-1] == '': dictionary.pop()
        dictionary = ['']+[s.rstrip('\r') for s in dictionary]+[' ']
        probabilities = run(session,tensor(crop))[0]
        if probabilities.shape[-1] != len(dictionary):
            raise ValueError('Recognition dictionary/class mismatch')
        indices = probabilities.argmax(axis=-1)
        keep = [i for i,v in enumerate(indices) if v and (i==0 or v!=indices[i-1])]
        return dict(text=''.join(dictionary[indices[i]] for i in keep),
                    confidence=float(np.mean([probabilities[i,indices[i]] for i in keep])) if keep else 0)
    with zipfile.ZipFile(archive) as z, output.open('x') as dest:
        for index, frame in enumerate(frames):
            started = time.monotonic_ns()
            evidence = [i for i in frame['images'] if i['role']=='analysis_input' and i['member']]
            if len(evidence)!=1: raise ValueError('Ambiguous selected evidence image')
            with Image.open(io.BytesIO(z.read(evidence[0]['member']))) as image:
                rgb = np.array(image.convert('RGB'))
            h,w = rgb.shape[:2]; ratio = min(1,1920/max(h,w))
            dw,dh = [max(32,math.ceil(math.floor(v*ratio+.5)/32)*32) for v in (w,h)]
            probabilities = run(sessions['ppocr-det.onnx'],tensor(cv2.resize(rgb,(dw,dh)),True))[0,0]
            count, labels, stats, _ = cv2.connectedComponentsWithStats((probabilities>=.3).astype(np.uint8),connectivity=4)
            sums = np.bincount(labels.ravel(),weights=probabilities.ravel())
            boxes = []
            for component in range(1,count):
                x,y,bw,bh,area = map(int,stats[component]); score = sums[component]/area
                if min(bw,bh)<3 or bh>bw*12 or score<.5: continue
                grow = math.floor(min(bw,bh)*.4+.5)
                left,top,right,bottom = [max(0,min(limit,math.floor(v*scale+.5))) for v,scale,limit in
                    ((x-grow,w/dw,w),(y-grow,h/dh,h),(x+bw+grow,w/dw,w),(y+bh+grow,h/dh,h))]
                if right-left>=2 and bottom-top>=2: boxes.append((left,top,right,bottom,float(score)))
            readings = []
            for left,top,right,bottom,score in boxes[:120]:
                crop = rgb[top:bottom,left:right]
                width = min(2048,max(1,math.floor((right-left)/(bottom-top)*48+.5)))
                crop = cv2.resize(crop,(width,48))
                orientation = np.zeros((1,3,48,192),dtype=np.float32)
                ow = min(192,width)
                orientation[:,:,:,:ow] = tensor(cv2.resize(crop,(ow,48)))
                prediction = run(sessions['ppocr-cls.onnx'],orientation).ravel()
                if len(prediction)>=2 and prediction[1]>=.9: crop=cv2.rotate(crop,cv2.ROTATE_180)
                readings.append(dict(box=[left,top,right,bottom],detectionConfidence=score,
                    english=recognize(crop,'ppocr-rec-en.onnx'),arabicColumnOrder=recognize(crop,'ppocr-rec-ar.onnx')))
            result = dict(scope='EXPLORATORY_HOST_OPTICAL_PROBE_NOT_PRODUCTION_REPLAY',frameId=frame['frameId'],
                evidenceHash=evidence[0]['evidenceSha256'],transmittedImageHashKnown=False,
                width=w,height=h,detectedBoxes=len(boxes),readings=readings,
                hostProbeMs=(time.monotonic_ns()-started)/1e6)
            dest.write(json.dumps(result,ensure_ascii=False)+'\n'); dest.flush()
            print(f'Completed private optical probe {index+1}/{len(frames)}',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path);parser.add_argument('frames',type=Path)
    parser.add_argument('--models',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    probe(require_private_path(args.archive),json.loads(require_private_path(args.frames).read_text()),
          args.models,require_private_path(args.output))
