#!/usr/bin/env python3
"""Create public update metadata from the already verified CI release assets."""
import hashlib
import json
from pathlib import Path
import re
import sys

PACKAGE = 'com.shadowpheonix.tradingdashboard'
MAX_SIZE = 100 * 1024 * 1024


def release_manifest(bundle):
    info = (bundle / 'build-info.txt').read_text()
    fields = dict(line.split(': ', 1) for line in info.splitlines())
    code = int(fields['Version code'])
    version = fields['Version']
    if not 0 < code <= 2147483647 or version != f'0.1.{code}':
        raise ValueError('Invalid version')
    if not re.fullmatch('[a-f0-9]{40}', fields['Commit']):
        raise ValueError('Invalid commit')
    cert = fields['Certificate SHA-256']
    if not re.fullmatch('[a-f0-9]{64}', cert):
        raise ValueError('Invalid certificate metadata')
    if f'Signer #1 certificate SHA-256 digest: {cert}' not in (bundle / 'signature.txt').read_text().splitlines():
        raise ValueError('Certificate metadata mismatch')
    apk = f'Trading-Dashboard-{version}.apk'
    path = bundle / apk
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_SIZE:
        raise ValueError('Invalid APK file')
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    checksums = (bundle / 'SHA256SUMS.txt').read_text().splitlines()
    if checksums not in ([f'{digest}  {apk}'], [f'{digest}  ./{apk}']):
        raise ValueError('APK checksum mismatch')
    return dict(versionCode=code, versionName=version, packageName=PACKAGE,
                apk=apk, sha256=digest, size=path.stat().st_size,
                certificateSha256=cert, commit=fields['Commit'])


if __name__ == '__main__':
    try:
        root = Path(sys.argv[1])
        (root / 'latest.json').write_text(json.dumps(release_manifest(root), indent=2) + '\n')
    except Exception:
        raise SystemExit('Release metadata invalid; no manifest generated.') from None
