#!/usr/bin/env python3
"""Human-run, dashboard-public-assets-only publisher with atomic manifests and retention."""
import fcntl
import hashlib
import importlib.util
import json
import os
import pwd
from pathlib import Path
import re
import shutil
import sys
import tempfile

ROOT = Path('/opt/trading-dashboard/app-updates')
MANAGED_ROOT = Path('/home/coder/HLTRADINGBOT/.dashboard-runtime/app-updates')
PERMANENT_CERTIFICATE = 'b638ac1ee919893fb716bc279f193230924d6db915785d1a9d1a7cf7fc309a35'
spec = importlib.util.spec_from_file_location('update_manifest', Path(__file__).resolve().parents[2] / 'scripts/update-manifest.py')
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)
APK_NAME = re.compile(r'Trading-Dashboard-0\.1\.([1-9][0-9]*)\.apk')


def publish(bundle, destination):
    """Tests use a temporary directory and synthetic assets, never real installation paths."""
    for name in ('build-info.txt', 'signature.txt', 'SHA256SUMS.txt', 'latest.json'):
        path = bundle / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 16384:
            raise ValueError('Invalid metadata')
    manifest = metadata.release_manifest(bundle)
    if json.loads((bundle / 'latest.json').read_text()) != manifest:
        raise ValueError('Manifest differs from verified assets')
    if destination.is_symlink():
        raise ValueError('Unsafe public directory')
    destination.mkdir(mode=0o755, parents=True, exist_ok=True)
    if (destination / '.publish.lock').is_symlink():
        raise ValueError('Unsafe lock')
    with (destination / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        latest = destination / 'latest.json'
        if latest.is_symlink():
            raise ValueError('Unsafe manifest')
        if latest.exists():
            previous = json.loads(latest.read_text())
            if previous['versionCode'] > manifest['versionCode']:
                raise ValueError('Cannot publish an older version')
            if previous['certificateSha256'] != manifest['certificateSha256']:
                raise ValueError('Permanent certificate differs')
            if previous['versionCode'] == manifest['versionCode'] and previous != manifest:
                raise ValueError('Cannot replace a version with different assets')
        for path in destination.iterdir():
            if APK_NAME.fullmatch(path.name) and (path.is_symlink() or not path.is_file()):
                raise ValueError('Unsafe APK in public directory')
        apk = destination / manifest['apk']
        if apk.exists():
            with apk.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != manifest['sha256']:
                    raise ValueError('Existing version has different bytes')
        # Both temporary files are private/unlisted until atomically installed.
        with tempfile.TemporaryDirectory(prefix='.publish-', dir=destination) as stage:
            staged = Path(stage) / manifest['apk']
            shutil.copyfile(bundle / manifest['apk'], staged)
            with staged.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != manifest['sha256']:
                    raise ValueError('Copy verification failed')
            staged.chmod(0o644)
            os.replace(staged, apk)
            staged_json = Path(stage) / 'latest.json'
            staged_json.write_text(json.dumps(manifest, indent=2) + '\n')
            staged_json.chmod(0o644)
            os.replace(staged_json, latest)
        versions = sorted((path for path in destination.iterdir() if APK_NAME.fullmatch(path.name)),
                          key=lambda path: int(APK_NAME.fullmatch(path.name)[1]), reverse=True)
        # Remove only exact versioned dashboard APK filenames, after latest is installed.
        retained = {apk, *[path for path in versions if path != apk][:2]}
        for path in versions:
            if path in retained:
                continue
            path.unlink()
        return manifest


def main(arguments=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    managed = len(arguments) == 2 and arguments[0] == '--managed'
    if managed:
        if (pwd.getpwuid(os.geteuid()).pw_name != 'coder' or MANAGED_ROOT.is_symlink()
                or not MANAGED_ROOT.is_dir() or MANAGED_ROOT.stat().st_uid != os.geteuid()):
            raise ValueError('Complete the one-time coder migration first.')
        bundle, destination = Path(arguments[1]), MANAGED_ROOT
        if metadata.release_manifest(bundle)['certificateSha256'] != PERMANENT_CERTIFICATE:
            raise ValueError('Permanent certificate differs')
    elif os.geteuid() == 0 and len(arguments) == 1:
        bundle, destination = Path(arguments[0]), ROOT
    else:
        raise ValueError('Use --managed as coder after migration, or the legacy human-root publisher.')
    os.umask(0o022)
    value = publish(bundle, destination)
    print(f"Published Trading Dashboard {value['versionName']}; kept the latest three APK versions.")


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('APK publication refused. Previous update metadata remains available; review the bundle and directory.') from None
