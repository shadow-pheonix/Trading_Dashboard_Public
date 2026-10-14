"""Synthetic migration/deployment tests; no privileged commands or live paths."""
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import stat
import subprocess
import sys
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

DEPLOY = Path(__file__).resolve().parents[1] / 'deploy'


def load(name, filename):
    loader = importlib.machinery.SourceFileLoader(name, str(DEPLOY / filename))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


control = load('coder_control', 'trading-dashboard-control')
manager = load('coder_manager', 'manage.py')
migration = load('coder_migration', 'migrate-to-coder.py')


class ControlCase(unittest.TestCase):
    def test_only_named_dashboard_operations(self):
        for action, service in [('restart-api', control.API), ('restart-https', control.HTTPS)]:
            self.assertEqual(control.command([action]), ['/usr/bin/systemctl', 'restart', service])
        self.assertEqual(control.command(['status'])[-2:], [control.API, control.HTTPS])
        for args in ([], ['restart-api', 'extra'], ['restart', 'hlmacdbot.service'], ['stop'],
                     ['check', '../etc'], ['check', 'a'*40, 'extra'], ['check', '$(id)']):
            with self.subTest(args=args), self.assertRaises(ValueError):
                control.command(args)
        with patch.object(control, 'RUNTIME', Path('/fixture')):
            command = control.command(['check', 'a'*40])
            self.assertEqual(command[:4], ['/usr/sbin/runuser', '-u', 'trading-dashboard', '--'])
            self.assertIn('-I', command)
            self.assertIn('-B', command)

    def test_check_output_is_suppressed_and_root_required(self):
        with patch.object(control.os, 'geteuid', return_value=1000), patch.object(control.subprocess, 'run') as run:
            self.assertEqual(control.main(), 1)
            run.assert_not_called()
        with patch.object(control.os, 'geteuid', return_value=0), patch.object(control.sys, 'argv', ['control', 'check', 'a'*40]), \
                patch.object(control.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run:
            self.assertEqual(control.main(), 0)
            self.assertEqual(run.call_args.kwargs['stdout'], control.subprocess.DEVNULL)
            self.assertEqual(run.call_args.kwargs['stderr'], control.subprocess.DEVNULL)


class ManagerCase(unittest.TestCase):
    def test_failed_health_restores_prior_code_without_credential_rewind(self):
        with tempfile.TemporaryDirectory() as td, patch.object(manager, 'RUNTIME', Path(td)):
            root = Path(td)
            releases = root / 'releases'
            releases.mkdir()
            old, new = releases / ('a'*40), releases / ('b'*40)
            old.mkdir(); new.mkdir()
            (root / 'current').symlink_to(old)
            private = root / 'dashboard.sqlite'
            private.write_bytes(b'unchanged-key-digests-fixture')
            with patch.object(manager, 'prepare', return_value=new), patch.object(manager, 'run') as run, \
                    patch.object(manager, 'restart_and_verify', side_effect=[RuntimeError('health failed'), None]):
                with self.assertRaises(RuntimeError):
                    manager.deploy('b'*40)
                self.assertEqual((root / 'current').resolve(), old)
                self.assertEqual(private.read_bytes(), b'unchanged-key-digests-fixture')
                self.assertEqual(run.call_args.args[-2:], ('check', 'b'*40))

    def test_repeat_deployment_and_successful_rollback(self):
        with tempfile.TemporaryDirectory() as td, patch.object(manager, 'RUNTIME', Path(td)):
            root = Path(td)
            releases = root / 'releases'; releases.mkdir()
            old, new = releases / ('a'*40), releases / ('b'*40)
            old.mkdir(); new.mkdir()
            (root / 'current').symlink_to(old)
            with patch.object(manager, 'prepare', return_value=new), patch.object(manager, 'run'), \
                    patch.object(manager, 'restart_and_verify') as restart:
                manager.deploy('b'*40)
                self.assertEqual((root / 'current').resolve(), new)
                self.assertEqual((root / 'previous').resolve(), old)
                with patch.object(manager, 'healthy', return_value=True):
                    manager.deploy('b'*40)
                restart.assert_called_once()
                manager.rollback()
                self.assertEqual((root / 'current').resolve(), old)
                self.assertEqual((root / 'previous').resolve(), new)

    def test_source_dirt_wrong_identity_and_release_symlinks(self):
        for values in ((b'b'*40,), (b'a'*40, b'b'*40), (b'a'*40, b'a'*40, b' M code')):
            with patch.object(manager, 'git', side_effect=values), self.assertRaises(ValueError):
                manager.verify_commit('a'*40)
        with self.assertRaises(ValueError):
            manager.verify_commit('../../unsafe')
        with tempfile.TemporaryDirectory() as td, patch.object(manager, 'RUNTIME', Path(td)):
            root = Path(td); (root / 'releases').mkdir()
            release = root / 'releases' / ('a'*40)
            release.symlink_to(root)
            with self.assertRaises(ValueError):
                manager.set_current(release)


class MigrationCase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.runtime = self.root / 'runtime'; self.runtime.mkdir()
        (self.runtime / 'releases' / ('a'*40)).mkdir(parents=True)
        self.public = self.root / 'public'; self.public.mkdir()
        for version in (36, 37, 38):
            (self.public / f'Trading-Dashboard-0.1.{version}.apk').write_bytes(b'fixture-apk')
        manifest = {'apk': 'Trading-Dashboard-0.1.38.apk', 'sha256': hashlib.sha256(b'fixture-apk').hexdigest(),
                    'certificateSha256': 'f'*64}
        (self.public / 'latest.json').write_text(json.dumps(manifest))
        self.backups = self.root / 'backups'; self.backups.mkdir()
        self.paths = {
            'RUNTIME': self.runtime, 'PUBLIC': self.public, 'BACKUPS': self.backups,
            'CONTROL': self.root / 'sbin/control', 'PREFLIGHT': self.root / 'lib/preflight.py',
            'SUDOERS': self.root / 'sudoers/control', 'API_DROPIN': self.root / 'units/api/managed.conf',
            'HTTPS_DROPIN': self.root / 'units/https/managed.conf', 'RECEIPT': self.root / 'data/receipt.json',
            'HOMES': (self.root, self.root / 'workspace'),
        }
        self.paths['HOMES'][1].mkdir()
        for name, value in self.paths.items():
            self.enterContext(patch.object(migration, name, value))
        self.enterContext(patch.object(migration, 'reviewed_source', return_value={
            'trading-dashboard-control': b'fixture-control', 'check-startup.py': b'fixture-preflight'}))
        self.enterContext(patch.object(migration.os, 'chown'))
        self.enterContext(patch.object(migration.os, 'fchown'))
        self.enterContext(patch.object(migration.pwd, 'getpwnam', side_effect=lambda name:
                                      SimpleNamespace(pw_uid=os.getuid() if name == 'coder' else 123, pw_gid=os.getgid())))
        self.enterContext(patch.object(migration.subprocess, 'check_output', side_effect=lambda args:
            b'fixture-acl' if 'getfacl' in args[0] else
            (b'trading-dashboard' if args[-1] == migration.SERVICES[0] else b'dashboard-proxy')))
        self.enterContext(patch.object(migration.shutil, 'copytree', side_effect=lambda source, target: target.mkdir()))
        self.enterContext(patch.object(migration.shutil, 'copy2', side_effect=lambda source, target: target.write_text('old-receipt')))

    def test_migration_moves_apks_preserves_private_data_and_targets_dashboard(self):
        private = self.root / 'private-config.json'; private.write_bytes(b'unchanged-private-fixture')
        with patch.object(migration, 'run') as run:
            migration.migrate('a'*40)
        self.assertFalse(self.public.is_symlink())
        self.assertTrue(self.public.is_dir())
        self.assertEqual(list(self.public.iterdir()), [])
        self.assertIn(f'BindReadOnlyPaths={self.runtime}/app-updates:{self.public}\n',
                      self.paths['HTTPS_DROPIN'].read_text())
        self.assertEqual(len(list(self.root.rglob('*.apk'))), 3)
        self.assertEqual(private.read_bytes(), b'unchanged-private-fixture')
        self.assertEqual((self.runtime / 'current').resolve(), self.runtime / 'releases' / ('a'*40))
        self.assertTrue(self.paths['RECEIPT'].is_file())
        self.assertIn('BindReadOnlyPaths=', self.paths['API_DROPIN'].read_text())
        self.assertIn(str(self.runtime), self.paths['API_DROPIN'].read_text())
        for call in run.call_args_list:
            args = list(map(str, call.args))
            self.assertFalse(any('hlmacdbot' in a or '.env' in a for a in args))
            if args[:2] == ['/usr/bin/systemctl', 'restart']:
                self.assertIn(args[-1], migration.SERVICES)

    def test_proxy_assets_are_mounted_outside_inaccessible_home(self):
        with patch.object(migration, 'run') as run:
            migration.migrate('a'*40)
        overrides = self.paths['HTTPS_DROPIN'].read_text()
        bind = next(line.split('=', 1)[1] for line in overrides.splitlines() if line.startswith('BindReadOnlyPaths='))
        source, destination = bind.split(':')
        self.assertEqual(Path(source), self.runtime / 'app-updates')
        self.assertEqual(Path(destination), self.public)
        self.assertFalse(Path(destination).is_symlink())
        self.assertNotIn('ProtectHome=', overrides)
        permissions = [str(call.args[2]) for call in run.call_args_list if str(call.args[0]) == '/usr/bin/setfacl']
        self.assertEqual(permissions, ['u:trading-dashboard:--x'])
        # The original home-denial policy remains valid; a same-path bind or a
        # public symlink into /home would fail these destination checks.

    def test_preflight_failure_changes_no_service_or_public_assets(self):
        calls = []

        def run(*args):
            calls.append(list(map(str, args)))
            if str(args[0]) == str(self.paths['CONTROL']):
                raise RuntimeError('fixture preflight failure')

        with patch.object(migration, 'run', side_effect=run), self.assertRaises(RuntimeError):
            migration.migrate('a'*40)
        self.assertFalse(self.public.is_symlink())
        self.assertEqual(len(list(self.public.glob('*.apk'))), 3)
        self.assertFalse(any('restart' in c for c in calls))
        for name in ('CONTROL', 'PREFLIGHT', 'SUDOERS', 'API_DROPIN', 'HTTPS_DROPIN', 'RECEIPT'):
            self.assertFalse(self.paths[name].exists())

    def test_restart_failure_rolls_back_permissions_units_and_assets(self):
        calls = []

        def run(*args):
            values = list(map(str, args)); calls.append(values)
            if values[:2] == ['/usr/bin/systemctl', 'restart'] and sum('restart' in c for c in calls) == 1:
                raise RuntimeError('fixture restart failure')

        with patch.object(migration, 'run', side_effect=run), self.assertRaises(RuntimeError):
            migration.migrate('a'*40)
        self.assertFalse(self.public.is_symlink())
        self.assertEqual(len(list(self.root.rglob('*.apk'))), 3)
        self.assertFalse((self.runtime / 'current').exists())
        self.assertTrue(any(any(a.startswith('--restore=') for a in c) for c in calls))
        self.assertFalse(self.paths['RECEIPT'].exists())

    def test_https_and_manifest_failures_restore_assets_and_both_units(self):
        for point in ('https-restart', 'manifest'):
            calls = []
            failed = False

            def run(*args):
                nonlocal failed
                values = list(map(str, args)); calls.append(values)
                match = (values[:3] == ['/usr/bin/systemctl', 'restart', migration.SERVICES[1]]
                         if point == 'https-restart' else values[-1].endswith('/app-updates/latest.json'))
                if match and not failed:
                    failed = True
                    raise ValueError('fixture failure')

            with self.subTest(point=point), patch.object(migration, 'run', side_effect=run), self.assertRaises(ValueError):
                migration.migrate('a'*40)
            self.assertTrue(failed)
            self.assertFalse(self.public.is_symlink())
            self.assertEqual(len(list(self.public.glob('*.apk'))), 3)
            self.assertFalse((self.runtime / 'app-updates').exists())
            self.assertFalse((self.runtime / 'current').exists())
            self.assertEqual([c[-1] for c in calls[-2:]], list(migration.SERVICES))
            for name in ('CONTROL', 'PREFLIGHT', 'SUDOERS', 'API_DROPIN', 'HTTPS_DROPIN', 'RECEIPT'):
                self.assertFalse(self.paths[name].exists())

    def test_unsafe_assets_fail_before_mutation(self):
        (self.public / 'extra').symlink_to(self.root)
        with self.assertRaises(ValueError):
            migration.validate_public(self.public)
        (self.public / 'extra').unlink()
        (self.public / 'Trading-Dashboard-0.1.38.apk').write_bytes(b'corrupt')
        with self.assertRaises(ValueError):
            migration.validate_public(self.public)

    def test_preflight_directory_is_traversable_under_private_umask(self):
        path = self.root / 'root-library/check.py'
        previous = os.umask(0o077)
        try:
            migration.write_root(path, b'fixture', 0o644)
        finally:
            os.umask(previous)
        self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o755)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o644)

    def test_effective_migrated_unit_keeps_isolation_and_parses(self):
        with patch.object(migration, 'run'):
            migration.migrate('a'*40)
        original = (DEPLOY / 'trading-dashboard-api.service').read_text()
        overrides = self.paths['API_DROPIN'].read_text()
        text = original + '\n' + overrides.replace(str(self.runtime) + '/current/venv/bin/python', sys.executable)
        for protection in ('User=trading-dashboard', 'NoNewPrivileges=yes', 'ProtectSystem=strict',
                           'ProtectHome=tmpfs', 'InaccessiblePaths=-/etc/hlmacdbot',
                           'ReadWritePaths=/var/lib/trading-dashboard'):
            self.assertIn(protection, text)
        unit = self.root / 'trading-dashboard-api.service'
        unit.write_text(text)
        (self.root / 'trading-dashboard.slice').write_text((DEPLOY / 'trading-dashboard.slice').read_text())
        subprocess.run(['systemd-analyze', 'verify', '--man=no', str(unit)], check=True)


class MigrationCommandCase(unittest.TestCase):
    def test_failure_identifies_safe_step_without_command_or_payload(self):
        cases = [
            (('/usr/bin/systemctl', 'restart', migration.SERVICES[1]), 'restart ' + migration.SERVICES[1]),
            (('/usr/bin/curl', '--output', '/dev/null', 'http://127.0.0.1:18080/healthz'), 'API health check'),
            (('/usr/bin/curl', '--output', '/dev/null', 'https://dashboard.example.test/app-updates/latest.json'), 'public APK manifest check'),
        ]
        for args, label in cases:
            failure = subprocess.CalledProcessError(7, ['fixture-private-value'], output=b'private-response-fixture')
            with self.subTest(label=label), patch.object(migration.subprocess, 'run', side_effect=failure):
                with self.assertRaisesRegex(ValueError, label + r' failed \(exit 7\)') as caught:
                    migration.run(*args)
                self.assertNotIn('private', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
