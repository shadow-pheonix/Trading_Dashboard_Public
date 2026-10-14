"""Exercise the real Caddy allowlist using synthetic public assets on loopback only.

Usage: python3 scripts/tests/smoke_update_proxy.py /path/to/caddy
No live configuration, services, TLS, credentials or bot data are used.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def main():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        assets = root / 'assets'
        assets.mkdir()
        (assets / 'latest.json').write_text('{"versionCode":40}')
        (assets / 'Trading-Dashboard-0.1.40.apk').write_bytes(b'synthetic APK')
        (assets / 'private.txt').write_text('synthetic private fixture')
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port = probe.getsockname()[1]
        # Adapt the actual repository Caddyfile, replacing only the host/root and API upstream.
        config = root / 'Caddyfile'
        text = (ROOT / 'backend/deploy/Caddyfile').read_text()
        text = text.replace('dashboard.example.invalid', f'http://127.0.0.1:{port}')
        text = text.replace('/opt/trading-dashboard/app-updates', str(assets))
        text = text.replace('reverse_proxy 127.0.0.1:18080', 'reverse_proxy 127.0.0.1:1')
        config.write_text(text)
        process = subprocess.Popen([sys.argv[1], 'run', '--config', str(config), '--adapter', 'caddyfile'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def request(path, method='GET'):
            try:
                with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}'+path, method=method), timeout=2) as response:
                    return response.status, response.read(), response.headers
            except urllib.error.HTTPError as error:
                with error: return error.code, error.read(), error.headers
        try:
            for _ in range(50):
                try: request('/app-updates/latest.json'); break
                except OSError: time.sleep(.1)
            status, data, headers = request('/app-updates/latest.json')
            assert status == 200 and json.loads(data)['versionCode'] == 40
            assert headers['Cache-Control'] == 'no-store'
            assert request('/app-updates/Trading-Dashboard-0.1.40.apk')[1] == b'synthetic APK'
            assert request('/app-updates/latest.json', 'HEAD')[0] == 200
            for path in ('/app-updates/', '/app-updates/private.txt', '/app-updates/.publish.lock',
                         '/app-updates/.publish-hidden/latest.json', '/app-updates/Trading-Dashboard-0.1.0.apk',
                         '/app-updates/Trading-Dashboard-0.1.40.apk/private.txt', '/v2/not-allowed'):
                assert request(path)[0] == 404, path
            assert request('/app-updates/latest.json', 'POST')[0] == 404
            # Private endpoints still route to the authenticated API, never to public files.
            assert request('/v2/dashboard')[0] == 502
            # Reproduce the failed migration: a public symlink crosses an
            # inaccessible home. Represent the corrected read-only mount with
            # hard links to the same public file inodes, outside that home.
            hidden = root / 'masked-home'
            hidden.mkdir()
            managed = hidden / 'app-updates'
            assets.rename(managed)
            assets.symlink_to(managed)
            try:
                hidden.chmod(0o000)
                assert request('/app-updates/latest.json')[0] == 403
                hidden.chmod(0o700)
                assets.unlink()
                assets.mkdir()
                for source in managed.iterdir():
                    os.link(source, assets / source.name)
                hidden.chmod(0o000)
                assert request('/app-updates/latest.json')[0] == 200
                assert request('/app-updates/Trading-Dashboard-0.1.40.apk')[1] == b'synthetic APK'
                assert request('/app-updates/private.txt')[0] == 404
            finally:
                hidden.chmod(0o700)
            print('Real Caddy HTTP checks passed: APK/manifest, HEAD, cache policy, blocked files/methods, API route; hidden-home symlink fails and external asset mount succeeds.')
        finally:
            process.terminate()
            process.wait(timeout=5)


if __name__ == '__main__': main()
