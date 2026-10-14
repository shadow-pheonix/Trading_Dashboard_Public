"""Offline public APK publication tests: no privileged commands or live paths."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('publisher', ROOT / 'backend/deploy/publish-app-update.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class AppUpdateCase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.public = self.root / 'public'

    def bundle(self, code, certificate='f'*64):
        path = self.root / f'bundle-{code}'
        path.mkdir(exist_ok=True)
        name = f'Trading-Dashboard-0.1.{code}.apk'
        apk = path / name
        apk.write_bytes(f'synthetic-offline-apk-{code}'.encode())
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (path / 'build-info.txt').write_text(f'Version: 0.1.{code}\nVersion code: {code}\nCommit: '+ 'a'*40 + f'\nCertificate SHA-256: {certificate}\n')
        (path / 'signature.txt').write_text(f'Signer #1 certificate SHA-256 digest: {certificate}\n')
        (path / 'SHA256SUMS.txt').write_text(f'{digest}  ./{name}\n')
        (path / 'latest.json').write_text(json.dumps(publisher.metadata.release_manifest(path)))
        return path

    def test_three_version_retention_numeric_sorting_and_only_public_files(self):
        for code in (8, 9, 10, 11, 12, 13):
            bundle = self.bundle(code)
            (bundle / 'private-secret.txt').write_text('synthetic-private-never-published')
            value = publisher.publish(bundle, self.public)
            self.assertEqual(code, value['versionCode'])
            self.assertEqual(code, json.loads((self.public / 'latest.json').read_text())['versionCode'])
            self.assertLessEqual(len(list(self.public.glob('*.apk'))), 3)
        self.assertEqual([11, 12, 13], sorted(int(publisher.APK_NAME.fullmatch(p.name)[1]) for p in self.public.glob('*.apk')))
        self.assertEqual(0o644, (self.public / 'latest.json').stat().st_mode & 0o777)
        self.assertEqual(0o644, (self.public / value['apk']).stat().st_mode & 0o777)
        self.assertFalse((self.public / 'private-secret.txt').exists())
        self.assertFalse(any(p.name.startswith('.publish-') for p in self.public.iterdir()))

    def test_checksum_failure_preserves_current_manifest_and_three_versions(self):
        for code in (1, 2, 3): publisher.publish(self.bundle(code), self.public)
        old = (self.public / 'latest.json').read_bytes()
        invalid = self.bundle(4)
        (invalid / 'Trading-Dashboard-0.1.4.apk').write_bytes(b'tampered')
        with self.assertRaises(ValueError): publisher.publish(invalid, self.public)
        self.assertEqual(old, (self.public / 'latest.json').read_bytes())
        self.assertEqual(3, len(list(self.public.glob('*.apk'))))

    def test_downgrade_and_signature_change_rejected_rerun_idempotent(self):
        publisher.publish(self.bundle(10), self.public)
        for bundle in (self.bundle(9), self.bundle(11, 'b'*64)):
            with self.assertRaises(ValueError): publisher.publish(bundle, self.public)
        self.assertEqual(10, publisher.publish(self.bundle(10), self.public)['versionCode'])

    def test_changed_immutable_version_and_symlink_rejected(self):
        publisher.publish(self.bundle(10), self.public)
        bundle = self.bundle(10)
        (bundle / 'build-info.txt').write_text((bundle / 'build-info.txt').read_text().replace('a'*40, 'b'*40))
        (bundle / 'latest.json').write_text(json.dumps(publisher.metadata.release_manifest(bundle)))
        with self.assertRaises(ValueError): publisher.publish(bundle, self.public)
        newer = self.bundle(11)
        (newer / 'signature.txt').unlink()
        (newer / 'signature.txt').symlink_to(bundle / 'signature.txt')
        with self.assertRaises(ValueError): publisher.publish(newer, self.public)

    def test_invalid_version_mismatched_manifest_and_oversized_metadata(self):
        for change in ('version', 'manifest', 'oversized'):
            bundle = self.bundle(20)
            if change == 'version':
                (bundle / 'build-info.txt').write_text((bundle / 'build-info.txt').read_text().replace('Version: 0.1.20', 'Version: 0.1.21'))
            elif change == 'manifest': (bundle / 'latest.json').write_text('{}')
            else: (bundle / 'signature.txt').write_text('x'*16385)
            with self.subTest(change=change), self.assertRaises(ValueError): publisher.publish(bundle, self.public)

    def test_cleanup_keeps_unrelated_files_and_current_manifest(self):
        publisher.publish(self.bundle(1), self.public)
        (self.public / 'unrelated.txt').write_text('keep')
        for code in (2, 3, 4): publisher.publish(self.bundle(code), self.public)
        self.assertEqual('keep', (self.public / 'unrelated.txt').read_text())
        self.assertEqual(4, json.loads((self.public / 'latest.json').read_text())['versionCode'])

    def test_coder_publisher_needs_migration_and_permanent_certificate(self):
        self.public.mkdir()
        owner = SimpleNamespace(pw_name='coder')
        bundle = self.bundle(38, publisher.PERMANENT_CERTIFICATE)
        with patch.object(publisher, 'MANAGED_ROOT', self.public), \
                patch.object(publisher.pwd, 'getpwuid', return_value=owner):
            publisher.main(['--managed', str(bundle)])
            self.assertEqual(38, json.loads((self.public / 'latest.json').read_bytes())['versionCode'])
            with self.assertRaises(ValueError):
                publisher.main(['--managed', str(self.bundle(39, 'b'*64))])
        self.assertEqual(38, json.loads((self.public / 'latest.json').read_bytes())['versionCode'])

    def test_managed_publisher_rejects_other_users_and_unprepared_directory(self):
        with patch.object(publisher, 'MANAGED_ROOT', self.public), \
                patch.object(publisher.pwd, 'getpwuid', return_value=SimpleNamespace(pw_name='coder')):
            with self.assertRaises(ValueError):
                publisher.main(['--managed', str(self.bundle(38))])
        self.public.mkdir()
        with patch.object(publisher, 'MANAGED_ROOT', self.public), \
                patch.object(publisher.pwd, 'getpwuid', return_value=SimpleNamespace(pw_name='runner')):
            with self.assertRaises(ValueError):
                publisher.main(['--managed', str(self.bundle(38))])


if __name__ == '__main__': unittest.main()
