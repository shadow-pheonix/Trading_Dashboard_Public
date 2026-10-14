import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


encoder = load('connection_encoder', SCRIPTS / 'encode-connection-key.py')
versioner = load('build_version', SCRIPTS / 'build-version.py')


class ConnectionSetupCase(unittest.TestCase):
    def test_existing_token_is_wrapped_without_overwrite_or_console_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'access-key.txt', Path(directory)/'connection-key.txt'
            source.write_text('x'*43 + '\n'); source.chmod(0o600)
            encoder.encode_file(source, target, 'https://dashboard.example.test')
            self.assertTrue(target.read_text().startswith('TD1.'))
            self.assertEqual('x'*43 + '\n', source.read_text())
            self.assertEqual(0o600, target.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                encoder.encode_file(source, target, 'https://dashboard.example.test')

    def test_env_files_symlinks_public_files_and_bad_origin_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, target = root/'access-key.txt', root/'output.txt'
            source.write_text('x'*43); source.chmod(0o600)
            link = root/'linked-key.txt'; link.symlink_to(source)
            for path in (root/'.env', link):
                with self.assertRaises((ValueError, OSError)):
                    encoder.encode_file(path, target, 'https://dashboard.example.test')
            source.chmod(0o644)
            with self.assertRaises(ValueError):
                encoder.encode_file(source, target, 'https://dashboard.example.test')
            source.chmod(0o600)
            with self.assertRaises(ValueError):
                encoder.encode_file(source, target, 'http://dashboard.example.test')
            self.assertFalse(target.exists())

    def test_new_repository_versions_continue_above_old_releases(self):
        self.assertEqual(51, versioner.build_version('50', '1'))
        self.assertEqual(52, versioner.build_version('50', '2'))
        self.assertEqual(1, versioner.build_version('0', '1'))
        for offset, run in [('50', '0'), ('-1', '1'), ('50', 'x'), ('50', '١'), ('2147483647', '1')]:
            with self.assertRaises(ValueError):
                versioner.build_version(offset, run)
