"""Publish safety checks use a fake gh executable, synthetic APK and no network."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'publish-release.sh'
SHA = 'a' * 40


class PublishCase(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=SCRIPT.parent)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'bin').mkdir()
        (self.root / 'dist').mkdir()
        apk = self.root / 'dist' / 'Trading-Dashboard-0.1.20.apk'
        apk.write_bytes(b'synthetic-apk-for-offline-test')
        (apk.parent / 'SHA256SUMS.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n')
        (apk.parent / 'signature.txt').write_text('Signer #1 certificate SHA-256 digest: ' + 'f'*64 + '\n')
        (apk.parent / 'build-info.txt').write_text('Version: 0.1.20\nVersion code: 20\nCommit: ' + SHA + '\nCertificate SHA-256: ' + 'f'*64 + '\n')
        (apk.parent / 'latest.json').write_text('{}')
        mock = self.root / 'bin' / 'gh'
        mock.write_text('#!' + sys.executable + '\n' + '''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with Path(os.environ['CALL_LOG']).open('a') as file:
    file.write(json.dumps(args) + '\\n')
case = os.environ['CASE']
if args[0] == 'api':
    if '--method' in args:
        raise SystemExit(1 if case == 'tag-create-denied' else 0)
    if case in ('existing', 'wrong-tag'):
        print('commit ' + ('b' * 40 if case == 'wrong-tag' else 'a' * 40))
        raise SystemExit(0)
    raise SystemExit(1)
if args[:2] == ['release', 'view']:
    raise SystemExit(0 if case == 'existing' else 1)
if args[:2] in (['release', 'create'], ['release', 'upload']):
    raise SystemExit(0)
raise SystemExit(2)
''')
        mock.chmod(0o755)
        self.env = {'PATH': str(mock.parent) + ':' + os.defpath, 'GITHUB_REF': 'refs/heads/main',
            'APP_VERSION_NAME': '0.1.20', 'APP_VERSION_CODE': '20', 'GITHUB_SHA': SHA,
            'GITHUB_REPOSITORY': 'synthetic/repo', 'RUNNER_TEMP': str(self.root),
            'GITHUB_STEP_SUMMARY': str(self.root / 'summary'), 'CASE': 'new',
            'CALL_LOG': str(self.root / 'calls')}

    def run_script(self):
        return subprocess.run(['bash', str(SCRIPT)], cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=5)

    def calls(self):
        path = self.root / 'calls'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_new_release_pins_commit_and_uploads_only_public_files(self):
        (self.root / 'dist' / 'not-an-asset-secret.txt').write_text('synthetic private file')
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        create_tag = next(c for c in calls if c[0] == 'api' and '--method' in c)
        self.assertIn('sha=' + SHA, create_tag)
        self.assertIn('ref=refs/tags/v0.1.20', create_tag)
        release = next(c for c in calls if c[:2] == ['release', 'create'])
        self.assertIn('--verify-tag', release)
        self.assertIn('--latest', release)
        assets = [c for c in release if c.startswith('dist/')]
        self.assertEqual(['dist/Trading-Dashboard-0.1.20.apk', 'dist/SHA256SUMS.txt',
                         'dist/signature.txt', 'dist/build-info.txt', 'dist/latest.json'], assets)
        self.assertEqual(20, json.loads((self.root / 'dist/latest.json').read_text())['versionCode'])
        self.assertFalse((self.root / 'dashboard-release-notes.md').exists())
        self.assertIn('releases/tag/v0.1.20', (self.root / 'summary').read_text())

    def test_rerun_restores_assets_without_moving_version_tag(self):
        self.env['CASE'] = 'existing'
        self.assertEqual(0, self.run_script().returncode)
        self.assertTrue(any(c[:2] == ['release', 'upload'] for c in self.calls()))
        self.assertFalse(any('--method' in c for c in self.calls()))
        self.assertFalse(any(c[:2] == ['release', 'create'] for c in self.calls()))

    def test_existing_version_at_other_commit_is_rejected(self):
        self.env['CASE'] = 'wrong-tag'
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertFalse(any(c[0] == 'release' for c in self.calls()))

    def test_tag_creation_failure_prevents_release(self):
        self.env['CASE'] = 'tag-create-denied'
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertFalse(any(c[0] == 'release' for c in self.calls()))

    def test_pull_request_cannot_publish(self):
        self.env['GITHUB_REF'] = 'refs/pull/3/merge'
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertEqual([], self.calls())

    def test_modified_apk_cannot_publish(self):
        (self.root / 'dist' / 'Trading-Dashboard-0.1.20.apk').write_bytes(b'modified')
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertEqual([], self.calls())

    def test_version_source_mismatch_cannot_publish(self):
        self.env['GITHUB_SHA'] = 'c' * 40
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertEqual([], self.calls())


if __name__ == '__main__':
    unittest.main()
