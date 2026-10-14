"""Synthetic env fixtures only; never open real env files or run privileged commands."""
from contextlib import redirect_stderr, redirect_stdout
import copy
import io
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from dashboard_backend.config import load_config
from dashboard_backend.validation import InvalidData
from tests import test_update

setup = test_update.setup


class AddressImportCase(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.environments = {name: self.directory / f'synthetic-{name}.env'
                             for name in ('main', 'vinay', 'madhu')}
        self.addresses = []
        for number, (name, path) in enumerate(self.environments.items()):
            fields = sorted(setup.PUBLIC_FIELDS if name == 'main'
                            else {f'HL_{model}_SUB_WALLET' for model in ('M1', 'M2', 'M3')})
            lines = ['HL_M1_SECRET_KEY=synthetic-secret-do-not-copy\n',
                     'TELEGRAM_BOT_TOKEN=$(touch must-not-execute)\n']
            for index, field in enumerate(fields):
                address = '0x' + format(100 * number + index + 1, '040x')
                self.addresses.append(address)
                lines.append(f'{field}="{address}" # public field\n')
            if name != 'main':
                lines.append('HL_B_M1_SUB_WALLET=ignored-invalid-extra-wallet\n')
            path.write_text(''.join(lines))
            path.chmod(0o600)
        self.real_fstat = os.fstat

    def root_metadata(self, fd):
        info = self.real_fstat(fd)
        return SimpleNamespace(st_uid=0, st_gid=0, st_mode=info.st_mode,
                               st_size=info.st_size, st_mtime_ns=info.st_mtime_ns,
                               st_ctime_ns=info.st_ctime_ns)

    def patches(self):
        return (patch.object(setup, 'ENVIRONMENTS', self.environments),
                patch.object(setup.os, 'geteuid', return_value=0),
                patch.object(setup.os, 'fstat', side_effect=self.root_metadata))

    def test_assignment_formats_and_unrelated_fields_are_ignored(self):
        address = self.addresses[0]
        for value in (address, f'"{address}"', f"'{address}'"):
            for prefix in ('', 'export '):
                lines = ['UNKNOWN="unterminated $(command)\n',
                         f'  {prefix}HL_M1_SUB_WALLET = {value} # comment\r\n']
                self.assertEqual({'HL_M1_SUB_WALLET': address},
                                 setup.parse_addresses(lines, {'HL_M1_SUB_WALLET'}))
        with self.assertRaises(InvalidData):
            setup.parse_addresses([], {'HL_M1_SECRET_KEY'})

    def test_original_equity_literals_missing_values_and_invalid_shell_text(self):
        assignment = f'HL_M1_SUB_WALLET={self.addresses[0]}\n'
        for value in ('16226.4', '"16226.4"', "'16226.4'", '16226.4 # comment'):
            parsed = setup.parse_addresses([assignment, 'export ORIGINAL_EQUITY=' + value],
                {'HL_M1_SUB_WALLET'}, include_equity=True)
            self.assertEqual(16226.4, parsed['ORIGINAL_EQUITY'])
        self.assertNotIn('ORIGINAL_EQUITY', setup.parse_addresses([assignment],
            {'HL_M1_SUB_WALLET'}, include_equity=True))
        for value in ('', '0', '-1', 'nan', 'inf', 'true', '$(touch never-run)', '123; touch never-run'):
            with self.subTest(value=value), self.assertRaises(InvalidData):
                setup.parse_addresses([assignment, 'ORIGINAL_EQUITY=' + value],
                    {'HL_M1_SUB_WALLET'}, include_equity=True)
        with self.assertRaises(InvalidData):
            setup.parse_addresses([assignment, 'ORIGINAL_EQUITY=100\n', 'ORIGINAL_EQUITY=100\n'],
                {'HL_M1_SUB_WALLET'}, include_equity=True)

    def test_instance_original_equity_is_imported_privately_and_validated(self):
        for index, path in enumerate(self.environments.values()):
            with path.open('a') as file: file.write(f'ORIGINAL_EQUITY={1000 + index}\n')
        mapping, uid, metadata = self.patches()
        with mapping, uid, metadata:
            value = setup.configure(test_update.UpdateCase().config())
        target = self.directory / 'equity-config.json'
        target.write_text(json.dumps(value))
        self.assertEqual([1000, 1001, 1002], [i.original_equity for i in load_config(target).instances])
        for invalid in (0, -1, True, '100', float('inf')):
            value['instances'][0]['originalEquity'] = invalid
            target.write_text(json.dumps(value))
            with self.assertRaises(InvalidData): load_config(target)
        previous = test_update.UpdateCase().config()
        for instance in previous['instances']: instance['originalEquity'] = 9999
        missing = setup.configure(previous,
            lambda path, required: {field: self.addresses[index] for index, field in enumerate(sorted(required))})
        self.assertTrue(all('originalEquity' not in instance for instance in missing['instances']))

    def test_missing_duplicate_blank_zero_invalid_and_shell_values_fail(self):
        address = self.addresses[0]
        candidates = ['', ' ', '0x' + '0' * 40, '0x123', f'$(echo {address})',
                      f'{address}; touch /tmp/never-execute', f'"{address}',
                      f'{address} trailing', '\\' + address]
        for value in candidates:
            with self.subTest(value=value), self.assertRaises(InvalidData):
                setup.parse_addresses([f'HL_M1_SUB_WALLET={value}\n'], {'HL_M1_SUB_WALLET'})
        with self.assertRaises(InvalidData):
            setup.parse_addresses([f'HL_M1_SUB_WALLET={address}\n'] * 2, {'HL_M1_SUB_WALLET'})
        with self.assertRaises(InvalidData):
            setup.parse_addresses(['UNKNOWN=' + 'x' * setup.MAX_BYTES], {'HL_M1_SUB_WALLET'})
        self.assertFalse((self.directory / 'must-not-execute').exists())

    def test_reads_three_synthetic_files_and_copies_no_unrelated_values(self):
        original = test_update.UpdateCase().config()
        mapping, uid, metadata = self.patches()
        with mapping, uid, metadata:
            value = setup.configure(copy.deepcopy(original))
        serialized = json.dumps(value)
        for address in self.addresses:
            self.assertEqual(1, serialized.count(address))
        for fragment in ('SECRET_KEY', 'TELEGRAM', 'synthetic-secret', 'must-not-execute', 'ignored-invalid'):
            self.assertNotIn(fragment, serialized)
        target = self.directory / 'validated.json'
        target.write_text(serialized)
        self.assertEqual([6, 3, 3], [len(i.accounts) for i in load_config(target).instances])
        self.assertEqual(original, {**value, 'instances': [
            {**{k: v for k, v in instance.items() if k != 'alertsPath'}, 'accounts': [{k: v for k, v in account.items()
                                     if k not in ('publicAddress', 'wallet')}
                                    for account in instance['accounts']]}
            for instance in value['instances']]})

    def test_root_scope_permissions_symlinks_and_changing_files_fail(self):
        path = self.environments['main']
        required = setup.PUBLIC_FIELDS
        mapping, uid, metadata = self.patches()
        with mapping, uid, metadata:
            self.assertEqual(6, len(setup.read_environment(path, required)))
            with self.assertRaises(InvalidData):
                setup.read_environment(self.directory / 'unapproved.env', required)
            path.chmod(0o644)
            with self.assertRaises(InvalidData):setup.read_environment(path, required)
            path.chmod(0o600)
            path.unlink();path.symlink_to(self.environments['vinay'])
            with self.assertRaises(OSError):setup.read_environment(path, required)
        mapping, uid, metadata = self.patches()
        with mapping, patch.object(setup.os, 'geteuid', return_value=1):
            with self.assertRaises(InvalidData):
                setup.read_environment(self.environments['vinay'], {'HL_M1_SUB_WALLET'})
        for changed in ('st_uid', 'st_gid', 'st_size', 'st_mtime_ns'):
            def bad_metadata(fd, changed=changed):
                result = self.root_metadata(fd)
                if changed in ('st_uid', 'st_gid'):setattr(result, changed, 1)
                elif changed == 'st_size':result.st_size = setup.MAX_BYTES + 1
                else:result.st_mtime_ns += calls[0];calls[0] += 1
                return result
            calls = [0]
            mapping, uid, _ = self.patches()
            with self.subTest(changed=changed), mapping, uid, patch.object(setup.os, 'fstat', side_effect=bad_metadata):
                with self.assertRaises(InvalidData):
                    setup.read_environment(self.environments['vinay'], {'HL_M1_SUB_WALLET', 'HL_M2_SUB_WALLET', 'HL_M3_SUB_WALLET'})

    def run_main(self, target):
        source = self.directory / 'original.json'
        source.write_text(json.dumps(test_update.UpdateCase().config()))
        real_stat = os.stat
        def helper_stat(path, *args, **kwargs):
            if str(path) == str(setup.__file__):
                return SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_gid=0)
            return real_stat(path, *args, **kwargs)
        out, err = io.StringIO(), io.StringIO()
        mapping, uid, metadata = self.patches()
        with mapping, uid, metadata, patch.object(setup.os, 'stat', side_effect=helper_stat), \
                patch.object(setup.sys, 'argv', ['configure-balances.py', str(source), str(target)]), \
                redirect_stdout(out), redirect_stderr(err):
            try:setup.main();failure = None
            except SystemExit as error:failure = str(error)
        for address in self.addresses:
            self.assertNotIn(address, out.getvalue() + err.getvalue() + str(failure))
        return source, failure

    def test_cli_succeeds_without_tty_and_creates_private_exclusive_output(self):
        target = self.directory / 'candidate.json'
        source, failure = self.run_main(target)
        self.assertIsNone(failure)
        self.assertEqual(0o600, stat.S_IMODE(target.stat().st_mode))
        self.assertEqual([6, 3, 3], [len(i.accounts) for i in load_config(target).instances])
        before = source.read_bytes(), target.read_bytes()
        _, failure = self.run_main(target)
        self.assertIsNotNone(failure)
        self.assertEqual(before, (source.read_bytes(), target.read_bytes()))

    def test_cli_missing_or_conflicting_address_leaves_no_candidate(self):
        target = self.directory / 'candidate.json'
        self.environments['madhu'].write_text('HL_M1_SUB_WALLET=\n')
        _, failure = self.run_main(target)
        self.assertIsNotNone(failure);self.assertFalse(target.exists())
        # Syntactically valid but duplicate across instances: final config validation fails.
        self.environments['madhu'].write_text('\n'.join(
            f'HL_M{index+1}_SUB_WALLET={address}' for index, address in enumerate(self.addresses[:3])))
        _, failure = self.run_main(target)
        self.assertIsNotNone(failure);self.assertFalse(target.exists())
