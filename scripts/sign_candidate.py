#!/usr/bin/env python3
"""Sign a validated candidate with the privately retained, pinned Android key."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

CERT = '349726219c7a59aee813745ff6d00599cf396f03cf6e2b3f48dcd29f023df9bd'
PACKAGE = 'com.abdullah.visionbridge.stable'

def main():
    p = argparse.ArgumentParser()
    for name in ('input', 'output', 'keystore', 'password-file', 'build-tools'):
        p.add_argument('--' + name, required=True, type=Path)
    a = p.parse_args()
    if a.output.exists():
        raise SystemExit('Refusing to overwrite an existing APK')
    env = os.environ.copy()
    env['VB_SIGNING_PASSWORD'] = a.password_file.read_text().strip()
    cert = subprocess.run(['keytool', '-exportcert', '-keystore', str(a.keystore),
        '-storepass:env', 'VB_SIGNING_PASSWORD', '-alias', 'visionbridge'],
        env=env, check=True, capture_output=True).stdout
    if hashlib.sha256(cert).hexdigest() != CERT:
        raise SystemExit('Signing certificate does not match the permanent identity')
    badging = subprocess.check_output([str(a.build_tools/'aapt'), 'dump', 'badging', str(a.input)], text=True)
    if "package: name='" + PACKAGE + "'" not in badging:
        raise SystemExit('Wrong application ID')
    a.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(a.build_tools/'apksigner'), 'sign', '--ks', str(a.keystore),
        '--ks-key-alias', 'visionbridge', '--ks-pass', 'env:VB_SIGNING_PASSWORD',
        '--key-pass', 'env:VB_SIGNING_PASSWORD', '--out', str(a.output), str(a.input)], env=env, check=True)
    verification = subprocess.check_output([str(a.build_tools/'apksigner'), 'verify', '--print-certs', str(a.output)], text=True)
    if 'certificate SHA-256 digest: ' + CERT not in verification:
        raise SystemExit('Output signature differs from pinned identity; do not distribute')
    print('Verified stable signature:', CERT)
    print('APK SHA256:', hashlib.sha256(a.output.read_bytes()).hexdigest())

if __name__ == '__main__':
    main()
