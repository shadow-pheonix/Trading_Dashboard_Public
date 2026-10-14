"""Startup preflight with synthetic dashboard files; no privileged operations."""
from contextlib import redirect_stderr, redirect_stdout, closing
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from dashboard_backend.storage import Store
from tests import test_update

BACKEND = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('startup_check', BACKEND / 'deploy/check-startup.py')
startup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(startup)


class StartupCheckCase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.config = self.root / 'config.json'
        self.config.write_text(json.dumps(test_update.UpdateCase().config()))
        self.database = self.root / 'dashboard.sqlite'
        Store(self.database).initialize()

    def test_preflight_imports_config_and_readonly_schema_without_collection(self):
        before = self.config.read_bytes(), self.database.read_bytes()
        with patch('dashboard_backend.files.BotFiles.build', side_effect=AssertionError('No bot reads')), \
                patch('dashboard_backend.market.BinancePrices.acquire', side_effect=AssertionError('No prices')), \
                patch('dashboard_backend.balances.Balances.get', side_effect=AssertionError('No balances')):
            startup.verify(BACKEND, self.config, self.database, runtime_check=lambda: None)
        self.assertEqual(before, (self.config.read_bytes(), self.database.read_bytes()))
        with closing(Store(self.database).connect(readonly=True)) as database:
            self.assertEqual(0, database.execute('SELECT count(*) FROM dashboard_keys').fetchone()[0])

    def test_missing_database_invalid_schema_config_runtime_and_access_fail(self):
        missing = self.root / 'never-create.sqlite'
        with self.assertRaises(sqlite3.OperationalError):
            startup.verify(BACKEND, self.config, missing, runtime_check=lambda: None)
        self.assertFalse(missing.exists())
        with closing(sqlite3.connect(self.database)) as database, database:
            database.execute('DROP TABLE dashboard_keys')
        with self.assertRaises(ValueError):
            startup.verify(BACKEND, self.config, self.database, runtime_check=lambda: None)
        self.config.write_text('invalid config')
        with self.assertRaises(ValueError):
            startup.verify(BACKEND, self.config, self.database, runtime_check=lambda: None)
        def unavailable():raise ImportError('synthetic dependency detail')
        with self.assertRaises(ImportError):
            startup.verify(BACKEND, self.config, self.database, runtime_check=unavailable)
        with patch.object(startup.os, 'access', return_value=False), self.assertRaises(ValueError):
            startup.verify(BACKEND, self.config, self.database, runtime_check=lambda: None)

    def test_cli_errors_are_sanitized_and_bad_arguments_fail(self):
        out, err = io.StringIO(), io.StringIO()
        with patch.object(startup, 'verify', side_effect=OSError('synthetic-private-value')), \
                redirect_stdout(out), redirect_stderr(err):
            self.assertEqual(1, startup.main([str(BACKEND), str(self.config), str(self.database)]))
            self.assertEqual(1, startup.main([]))
        self.assertNotIn('synthetic-private-value', out.getvalue() + err.getvalue())
        self.assertNotIn(str(self.root), out.getvalue() + err.getvalue())
