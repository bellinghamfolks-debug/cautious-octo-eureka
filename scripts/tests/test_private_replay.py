import json
from pathlib import Path
import sys,tempfile,unittest,zipfile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from replay_diagnostic_bundle import REPOSITORY,rebuild,require_private_path

class PrivateReplayTest(unittest.TestCase):
    def test_rejects_repository_paths(self):
        with self.assertRaises(ValueError):require_private_path(REPOSITORY/'fixtures/private.zip')
    def test_legacy_inference_cannot_become_wire_proof(self):
        es=[dict(type='FRAME_SELECTED_FOR_ANALYSIS',frameId='synthetic',traceId='trace',epochMs=1),
            dict(type='ANALYSIS_DISPATCH_STARTED',frameId='synthetic',epochMs=2),dict(type='LIVE_FRAME_SENT',epoch=2,epochMs=3),
            dict(type='TTS_UTTERANCE_STARTED',text='synthetic words',epochMs=4)]
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'synthetic.zip'
            with zipfile.ZipFile(p,'w') as z:z.writestr('sessions/test/events.jsonl','\n'.join(map(json.dumps,es)))
            data=rebuild(p)
            self.assertEqual(0,data['summary']['explicitBoundCurrent'])
            self.assertEqual('synthetic',data['turns'][0]['inferredFrameId'])
            self.assertIn('NOT_WIRE_PROOF',data['turns'][0]['association'])
            self.assertIn('UNBOUND',data['speech'][0]['association'])
    def test_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'synthetic.zip'
            with zipfile.ZipFile(p,'w') as z:z.writestr('../outside','synthetic')
            with self.assertRaises(ValueError):rebuild(p)
