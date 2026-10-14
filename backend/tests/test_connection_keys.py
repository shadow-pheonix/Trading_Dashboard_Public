import base64
import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from dashboard_backend.connection_keys import connection_key
from dashboard_backend.storage import Store


class ConnectionKeyCase(unittest.TestCase):
    def test_encoded_key_carries_origin_and_token_without_plain_url(self):
        url, token = 'https://dashboard.example.test', 'x' * 43
        code = connection_key(url + '/', token)
        self.assertTrue(code.startswith('TD1.'))
        self.assertNotIn(url, code)
        self.assertNotIn(token, code)
        decoded = json.loads(base64.urlsafe_b64decode(code[4:] + '=' * (-len(code[4:]) % 4)))
        self.assertEqual({'url': url, 'key': token}, decoded)

    def test_provisioned_code_authenticates_with_only_its_inner_token(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / 'keys.sqlite')
            store.initialize()
            output = Path(directory) / 'private-key.txt'
            with patch('dashboard_backend.storage.secrets.token_urlsafe', return_value='x' * 43):
                store.provision('main', output, 'https://dashboard.example.test')
            self.assertEqual(connection_key('https://dashboard.example.test', 'x' * 43), output.read_text().strip())
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            self.assertEqual('main', store.authenticate('x' * 43))
            self.assertIsNone(store.authenticate(output.read_text().strip()))
            with contextlib.closing(store.connect(True)) as db:
                self.assertNotIn('example.test', str(db.execute('SELECT * FROM dashboard_keys').fetchall()))
            with self.assertRaises(FileExistsError):
                store.provision('main', output, 'https://dashboard.example.test')

    def test_invalid_origin_or_token_fails_without_exposing_input(self):
        urls = ['http://dashboard.example.test', 'https://name:password@dashboard.example.test',
                'https://dashboard.example.test/path', 'https://dashboard.example.test?',
                'https://dashboard.example.test#', 'https://dashboard.example.test:0',
                'https://dashboard.example.test:65536', 'https://dashboard.example.test\n',
                'https://', 'unknown']
        for url in urls:
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, '^Invalid dashboard connection settings.$'):
                connection_key(url, 'x' * 43)
        for token in ('short', 'x' * 257, 'x' * 40 + '\r\n', 'x' * 40 + ' ', 'é' * 43):
            with self.assertRaisesRegex(ValueError, '^Invalid dashboard connection settings.$'):
                connection_key('https://dashboard.example.test', token)

    def test_bad_url_does_not_create_file_or_register_token(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / 'keys.sqlite')
            store.initialize()
            output = Path(directory) / 'private-key.txt'
            with self.assertRaises(ValueError):
                store.provision('main', output, 'http://dashboard.example.test')
            self.assertFalse(output.exists())
            with contextlib.closing(store.connect(True)) as db:
                self.assertEqual(0, db.execute('SELECT COUNT(*) FROM dashboard_keys').fetchone()[0])
