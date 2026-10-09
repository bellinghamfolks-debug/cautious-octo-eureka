#!/usr/bin/env python3
"""Experimental Flutter AOT asset-path test. Must re-sign after patching."""
import sys, zipfile, hashlib, re
from pathlib import Path
MANIFEST='assets/flutter_assets/AssetManifest.bin'
APP='lib/arm64-v8a/libapp.so'
ASSET='assets/flutter_assets/packages/features/assets/translations/'
FROM=b'assets/translations/en-US.json'
TO=b'assets/translations/ar-SA.json'
OLD=b'packages/features/assets/translations/en-US.json'
NEW=b'packages/features/assets/translations/ar-SA.json'
def entry(k):
    assert len(k)==48
    return b'\x07\x30'+k+b'\x0c\x01\x0d\x01\x07\x05asset\x07\x30'+k
def transform(name,data):
    if name==APP:
        assert data.count(FROM)==1,('AOT lookup occurrence',data.count(FROM))
        assert len(FROM)==len(TO)
        return data.replace(FROM,TO)
    if name==MANIFEST:
        assert data[:2]==b'\x0d\xfe', 'Unsupported asset manifest encoding'
        assert data.count(entry(OLD))==1
        assert NEW not in data
        result=data.replace(entry(OLD),entry(OLD)+entry(NEW))
        return result[:2]+(int.from_bytes(result[2:4],'little')+1).to_bytes(2,'little')+result[4:]
    return data
def main():
    old,output=map(Path,sys.argv[1:3])
    changed=[]
    with zipfile.ZipFile(old) as zin,zipfile.ZipFile(output,'w',allowZip64=True) as zout:
        assert zin.testzip() is None
        assert zin.read(ASSET+'en-US.json')==zin.read(ASSET+'ar-SA.json')
        for inf in zin.infolist():
            name=inf.filename
            if re.fullmatch(r'META-INF/[^/]+\.(?:SF|RSA|DSA|EC|MF)',name,re.I):continue
            blob=zin.read(name)
            patched=transform(name,blob)
            if blob!=patched:changed.append(name)
            out=zipfile.ZipInfo(name,inf.date_time)
            out.compress_type=zipfile.ZIP_STORED if name in (APP,'resources.arsc') else inf.compress_type
            out.external_attr=inf.external_attr
            zout.writestr(out,patched)
    assert sorted(changed)==sorted([MANIFEST,APP]),changed
    with zipfile.ZipFile(output) as z:
        assert z.testzip() is None
        assert z.read(APP).count(TO)==1
        assert z.read(MANIFEST).count(NEW)==2
    print('Modified only:',', '.join(changed),'unsigned bytes',output.stat().st_size)
if __name__=='__main__':main()
