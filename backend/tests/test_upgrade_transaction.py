"""Real upgrade/rollback shell flow, using a temporary filesystem and mocked privileged tools."""
import os
from contextlib import closing
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

DEPLOY=Path(__file__).resolve().parents[1]/'deploy'

class UpgradeTransactionCase(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.root=Path(temp.name);self.install=self.root/'opt';self.data=self.root/'data'
        self.proxy=self.root/'proxy';self.backups=self.root/'backups';self.source=self.root/'source'
        self.bin=self.root/'bin';self.bin.mkdir();self.sha='a'*40
        for path in [self.install/'backend/dashboard_backend',self.install/'venv/bin',self.install/'https',
                     self.data/'keys',self.proxy,self.source/'backend/deploy',self.source/'backend/dashboard_backend']:
            path.mkdir(parents=True,exist_ok=True)
        for name in ['update.sh','rollback.sh']:
            script=(DEPLOY/name).read_text()
            script=script.replace("[[ ${EUID} -eq 0 ]] || fail 'A human administrator must run this update with sudo.'",':')
            script=script.replace("[[ ${EUID} -eq 0 ]] || fail 'A human administrator must run rollback with sudo.'",':')
            for old,new in [('/opt/trading-dashboard',self.install),('/var/lib/trading-dashboard',self.data),
                            ('/etc/trading-dashboard',self.proxy),('/var/backups/trading-dashboard',self.backups)]:
                script=script.replace(old,str(new))
            (self.source/'backend/deploy'/name).write_text(script)
        (self.install/'venv/bin/python').symlink_to(sys.executable)
        self.executable(self.install/'https/caddy','#!/bin/sh\nexit 0\n')
        for module in ['__init__.py','__main__.py','api.py','alerts.py','balances.py','config.py','files.py','grouping.py','market.py','storage.py','validation.py']:
            (self.source/'backend/dashboard_backend'/module).write_text('# new '+module+'\n')
        (self.install/'backend/dashboard_backend/api.py').write_text('# old API\n')
        (self.install/'INSTALLED_COMMIT').write_text('b'*40+'\n')
        (self.install/'requirements.txt').write_text('unchanged\n')
        (self.source/'backend/requirements.txt').write_text('unchanged\n')
        (self.proxy/'Caddyfile').write_text('old proxy\n')
        (self.source/'backend/deploy/Caddyfile').write_text('new proxy\n')
        # Private setup is tested separately; this stub supplies an already validated candidate.
        (self.source/'backend/deploy/configure-balances.py').write_text('import pathlib,sys\npathlib.Path(sys.argv[2]).write_text("new private config\\n")\n')
        (self.source/'backend/deploy/check-startup.py').write_text(
            'import os,pathlib,stat,sys\n'
            'root=pathlib.Path(sys.argv[1])\n'
            'assert all(stat.S_IMODE(p.stat().st_mode)==0o755 for p in (root,root/"dashboard_backend"))\n'
            'assert stat.S_IMODE(root.parent.stat().st_mode)==0o710\n'
            'assert stat.S_IMODE(pathlib.Path(sys.argv[2]).stat().st_mode)==0o640\n'
            'assert stat.S_IMODE((root.parent/"configure-balances.py").stat().st_mode)==0o600\n'
            'pathlib.Path(root.parent.parent/"preflight-passed").touch()\n')
        (self.data/'config.json').write_text('old private config\n')
        (self.data/'keys/main-access-key.txt').write_text('synthetic-login-key\n')
        with closing(sqlite3.connect(self.data/'dashboard.sqlite')) as db, db:
            db.execute('CREATE TABLE keys(value TEXT)');db.execute('INSERT INTO keys VALUES(?)',('synthetic-key-hash',))
        self.executable(self.bin/'git',f'''#!{sys.executable}
import pathlib,sys
args=sys.argv[1:];root=pathlib.Path(args[args.index('-C')+1])
if '--show-toplevel' in args:print(root)
elif 'rev-parse' in args:print({'a'*40!r})
elif 'show' in args:sys.stdout.write((root/args[-1].split(':',1)[1]).read_text())
''')
        self.executable(self.bin/'install',f'''#!{sys.executable}
import subprocess,sys
args=sys.argv[1:];clean=[];i=0
while i<len(args):
    if args[i] in ('-o','-g'):i+=2
    else:clean.append(args[i]);i+=1
# Real install preserves umask/directory semantics; only privileged ownership is mocked.
subprocess.run(['/usr/bin/install',*clean],check=True)
''')
        self.executable(self.bin/'chown','#!/bin/sh\nexit 0\n')
        self.executable(self.bin/'chgrp','#!/bin/sh\nexit 0\n')
        self.executable(self.bin/'runuser','#!/bin/sh\nshift 3\nexec "$@"\n')
        self.executable(self.bin/'curl','#!/bin/sh\nexit 0\n')
        self.executable(self.bin/'systemctl',f'''#!{sys.executable}
import os,pathlib,sys
root=pathlib.Path({str(self.root)!r})
with (root/'services.log').open('a') as file:file.write(' '.join(sys.argv[1:])+'\\n')
if sys.argv[1]=='start' and os.environ.get('FAIL_ONCE')=='1' and not (root/'failed').exists():
    (root/'failed').touch();sys.exit(1)
''')
        self.env=dict(os.environ,PATH=str(self.bin)+os.pathsep+os.environ['PATH'])

    def executable(self,path,source):path.write_text(source);path.chmod(0o700)

    def update(self,fail=False):
        return subprocess.run(['bash',str(self.source/'backend/deploy/update.sh'),self.sha],
            env=dict(self.env,FAIL_ONCE='1' if fail else '0'),capture_output=True,text=True)

    def assert_credentials_preserved(self):
        self.assertEqual('synthetic-login-key\n',(self.data/'keys/main-access-key.txt').read_text())
        with closing(sqlite3.connect(self.data/'dashboard.sqlite')) as db, db:
            self.assertEqual([('synthetic-key-hash',)],db.execute('SELECT value FROM keys').fetchall())
        log=self.root/'services.log'
        for line in log.read_text().splitlines() if log.exists() else []:self.assertNotIn('hlmacdbot',line)

    def rollback(self,backup):
        return subprocess.run(['bash',str(self.source/'backend/deploy/rollback.sh'),str(backup)],
            env=self.env,capture_output=True,text=True)

    def test_successful_update_and_explicit_rollback_preserve_keys(self):
        result=self.update();self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('# new api.py\n',(self.install/'backend/dashboard_backend/api.py').read_text())
        import stat
        for path in (self.install/'backend',self.install/'backend/dashboard_backend'):
            self.assertEqual(0o755,stat.S_IMODE(path.stat().st_mode),
                             'Installed code directories must be traversable by the service user')
        self.assertEqual('new private config\n',(self.data/'config.json').read_text());self.assert_credentials_preserved()
        self.assertTrue((self.install/'preflight-passed').exists())
        backup=next(self.backups.glob('v2-*'))
        with closing(sqlite3.connect(backup/'dashboard.sqlite')) as db, db:
            self.assertEqual([('synthetic-key-hash',)],db.execute('SELECT value FROM keys').fetchall())
        result=self.rollback(backup);self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('# old API\n',(self.install/'backend/dashboard_backend/api.py').read_text())
        self.assertEqual('old private config\n',(self.data/'config.json').read_text());self.assert_credentials_preserved()

    def test_failed_start_automatically_restores_old_dashboard(self):
        result=self.update(fail=True);self.assertNotEqual(0,result.returncode)
        self.assertEqual('# old API\n',(self.install/'backend/dashboard_backend/api.py').read_text())
        self.assertEqual('old private config\n',(self.data/'config.json').read_text())
        self.assertEqual('old proxy\n',(self.proxy/'Caddyfile').read_text());self.assert_credentials_preserved()

    def test_rollback_recovers_interrupted_directory_swap(self):
        result=self.update();self.assertEqual(0,result.returncode,result.stderr)
        backup=next(self.backups.glob('v2-*'));shutil.rmtree(self.install/'backend')
        result=self.rollback(backup);self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('# old API\n',(self.install/'backend/dashboard_backend/api.py').read_text());self.assert_credentials_preserved()

    def test_failed_address_import_never_stops_services_or_changes_installed_files(self):
        (self.source/'backend/deploy/configure-balances.py').write_text(
            'import sys\nsys.exit("Synthetic public address validation failure")\n')
        result=self.update();self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.root/'services.log').exists())
        self.assertEqual('# old API\n',(self.install/'backend/dashboard_backend/api.py').read_text())
        self.assertEqual('old private config\n',(self.data/'config.json').read_text())
        self.assertEqual('old proxy\n',(self.proxy/'Caddyfile').read_text())
        self.assertEqual('b'*40+'\n',(self.install/'INSTALLED_COMMIT').read_text())
        self.assert_credentials_preserved()

    def test_service_identity_preflight_failure_never_stops_or_mutates_installation(self):
        (self.source/'backend/deploy/check-startup.py').write_text('import sys\nsys.exit(1)\n')
        result=self.update();self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.root/'services.log').exists())
        self.assertEqual('# old API\n',(self.install/'backend/dashboard_backend/api.py').read_text())
        self.assertEqual('old private config\n',(self.data/'config.json').read_text())
        self.assertEqual('old proxy\n',(self.proxy/'Caddyfile').read_text())
        self.assert_credentials_preserved()
