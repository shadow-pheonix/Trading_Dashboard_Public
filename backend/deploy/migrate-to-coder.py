#!/usr/bin/env python3
"""ONE-TIME HUMAN ROOT migration; no bot reads, writes, service control or env access."""
import hashlib
import fcntl
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import subprocess
import sys
import tempfile

REPO = Path('/home/coder/HLTRADINGBOT/Trading_Dashboard')
RUNTIME = Path('/home/coder/HLTRADINGBOT/.dashboard-runtime')
PUBLIC = Path('/opt/trading-dashboard/app-updates')
BACKUPS = Path('/var/backups/trading-dashboard-management')
CONTROL = Path('/usr/local/sbin/trading-dashboard-control')
PREFLIGHT = Path('/usr/local/lib/trading-dashboard/check-startup.py')
SUDOERS = Path('/etc/sudoers.d/trading-dashboard-coder')
API_DROPIN = Path('/etc/systemd/system/trading-dashboard-api.service.d/80-coder-management.conf')
HTTPS_DROPIN = Path('/etc/systemd/system/trading-dashboard-https.service.d/80-coder-management.conf')
RECEIPT = Path('/var/lib/trading-dashboard/coder-management.json')
HOMES = (Path('/home/coder'), Path('/home/coder/HLTRADINGBOT'))
SERVICES = ('trading-dashboard-api.service', 'trading-dashboard-https.service')
SUDO_TEXT = '''# Dashboard-only; installed once by a human root administrator.
coder ALL=(root) NOPASSWD: /usr/local/sbin/trading-dashboard-control restart-api, /usr/local/sbin/trading-dashboard-control restart-https, /usr/local/sbin/trading-dashboard-control status, /usr/local/sbin/trading-dashboard-control check *
'''


def git(*args):
    return subprocess.check_output(['/usr/sbin/runuser', '-u', 'coder', '--', '/usr/bin/git', '-C', str(REPO), *args])


def run(*args):
    values = [str(a) for a in args]
    label = 'dashboard prerequisite'
    if values[0] == str(CONTROL):
        label = 'candidate startup check'
    elif values[0] == '/usr/bin/systemctl':
        label = 'dashboard daemon reload'
        if len(values) == 3 and values[1] == 'restart' and values[2] in SERVICES:
            label = 'restart ' + values[2]
    elif values[0] == '/usr/bin/curl':
        label = 'API health check' if values[-1] == 'http://127.0.0.1:18080/healthz' else 'public APK manifest check'
    print(f'Dashboard step: {label}', flush=True)
    try:
        subprocess.run(values, check=True)
    except subprocess.CalledProcessError as error:
        # No command, exception payload, configuration or response body is exposed.
        raise ValueError(f'{label} failed (exit {error.returncode})') from None


def reviewed_source(commit):
    if not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('Provide the exact reviewed commit.')
    if any(git('rev-parse', ref).decode().strip() != commit for ref in ('HEAD', 'origin/main')):
        raise ValueError('Migration requires the reviewed pushed clean checkout.')
    if git('status', '--porcelain=v1', '--untracked-files=all').strip():
        raise ValueError('Migration checkout has local changes.')
    return {name: git('show', f'{commit}:backend/deploy/{name}')
            for name in ('trading-dashboard-control', 'check-startup.py')}


def validate_public(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Expected the existing regular public APK directory.')
    for path in directory.iterdir():
        if (path.is_symlink() or not path.is_file() or not
                (path.name in ('latest.json', '.publish.lock') or
                 re.fullmatch(r'Trading-Dashboard-0\.1\.[1-9][0-9]*\.apk', path.name))):
            raise ValueError('Unexpected entry in the public APK directory.')
    manifest = json.loads((directory / 'latest.json').read_bytes())
    name = manifest.get('apk', '')
    if not re.fullmatch(r'Trading-Dashboard-0\.1\.[1-9][0-9]*\.apk', name):
        raise ValueError('Invalid current public APK identity.')
    with (directory / name).open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != manifest['sha256']:
            raise ValueError('Current public APK checksum failed.')
    return manifest


def write_root(path, data, mode):
    if path.is_symlink() or path.exists():
        raise ValueError('Refusing to overwrite an existing migration control file.')
    new_parent = not path.parent.exists()
    path.parent.mkdir(parents=True, exist_ok=True)
    if new_parent:
        path.parent.chmod(0o755)
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(data)
        handle.flush()
        os.fchown(handle.fileno(), 0, 0)
        os.fchmod(handle.fileno(), mode)
        os.fsync(handle.fileno())


def migrate(commit, server_url='https://dashboard.example.invalid'):
    from urllib.parse import urlsplit
    endpoint = urlsplit(server_url)
    if (endpoint.scheme != 'https' or not endpoint.hostname or endpoint.username is not None or
            endpoint.password is not None or endpoint.query or endpoint.fragment or endpoint.path not in ('', '/') or
            any(c.isspace() for c in server_url)):
        raise ValueError('Provide the private dashboard HTTPS origin.')
    sources = reviewed_source(commit)
    if RECEIPT.exists():
        raise ValueError('Management is already configured; use coder deployment commands.')
    for path in (CONTROL, PREFLIGHT, SUDOERS, API_DROPIN, HTTPS_DROPIN):
        if path.exists() or path.is_symlink():
            raise ValueError('Migration target already exists; refusing a partial installation.')
    owner = pwd.getpwnam('coder')
    if RUNTIME.is_symlink() or RUNTIME.resolve() != RUNTIME or RUNTIME.stat().st_uid != owner.pw_uid:
        raise ValueError('Prepare the coder-owned runtime first.')
    if (RUNTIME / 'current').exists() or (RUNTIME / 'current').is_symlink():
        raise ValueError('A managed runtime is already active.')
    if (RUNTIME / 'app-updates').exists() or (RUNTIME / 'app-updates').is_symlink():
        raise ValueError('Managed public assets already exist.')
    if any(p.is_symlink() or p.resolve() != p for p in HOMES):
        raise ValueError('Unexpected workspace parent layout.')
    for service in SERVICES:
        run('/usr/bin/systemctl', 'is-active', '--quiet', service)
    # Reject a proxy with root privileges before delegating writable public assets.
    api_user, https_user = [subprocess.check_output(['/usr/bin/systemctl', 'show', '--property=User',
                                                   '--value', service]).decode().strip() for service in SERVICES]
    if api_user != 'trading-dashboard' or not https_user or pwd.getpwnam(https_user).pw_uid == 0:
        raise ValueError('Dashboard services must use isolated unprivileged accounts.')
    manifest = validate_public(PUBLIC)
    backup = Path(tempfile.mkdtemp(prefix='migration-', dir=BACKUPS))
    backup.chmod(0o700)
    acl = subprocess.check_output(['/usr/bin/getfacl', '--absolute-names', '--numeric', *map(str, HOMES)])
    (backup / 'workspace.acl').write_bytes(acl)
    (backup / 'workspace.acl').chmod(0o600)
    # Backup code/install receipt; private dashboard config/database/keys are never replaced.
    shutil.copytree('/opt/trading-dashboard/backend', backup / 'legacy-backend')
    shutil.copy2('/opt/trading-dashboard/INSTALLED_COMMIT', backup / 'INSTALLED_COMMIT')
    assets_info = {path.name: (path.stat().st_uid, path.stat().st_gid, path.stat().st_mode & 0o777)
                   for path in [PUBLIC, *PUBLIC.iterdir()]}
    (backup / 'public-file-metadata.json').write_text(json.dumps(assets_info))
    (backup / 'public-file-metadata.json').chmod(0o600)
    api = (f'[Service]\nWorkingDirectory={RUNTIME}/current/backend\nExecStart=\n'
           f'ExecStart={RUNTIME}/current/venv/bin/python -m dashboard_backend --config '
           '/var/lib/trading-dashboard/config.json --database /var/lib/trading-dashboard/dashboard.sqlite serve --port 18080\n'
           f'BindReadOnlyPaths={RUNTIME}\n').encode()
    # The proxy uses ProtectHome=yes. Mount public files at its existing /opt
    # path instead of making it follow a symlink into its inaccessible /home.
    https = f'[Service]\nBindReadOnlyPaths={RUNTIME}/app-updates:{PUBLIC}\n'.encode()
    created = []
    moved = False
    acl_changed = False
    reloaded = False
    try:
        # Traversal-only ACLs; no directory listing or access to coder credentials.
        for account in (api_user,):
            run('/usr/bin/setfacl', '-m', f'u:{account}:--x', *HOMES)
            acl_changed = True
        for path, data, mode in ((CONTROL, sources['trading-dashboard-control'], 0o755),
                                 (PREFLIGHT, sources['check-startup.py'], 0o644),
                                 (API_DROPIN, api, 0o644), (HTTPS_DROPIN, https, 0o644)):
            created.append(path)
            write_root(path, data, mode)
        sudo_candidate = backup / 'sudoers-candidate'
        sudo_candidate.write_text(SUDO_TEXT)
        sudo_candidate.chmod(0o440)
        run('/usr/sbin/visudo', '-cf', sudo_candidate)
        # Check committed modules as the service user before any dashboard restart.
        run(CONTROL, 'check', commit)
        created.append(SUDOERS)
        write_root(SUDOERS, SUDO_TEXT.encode(), 0o440)
        shutil.move(PUBLIC, RUNTIME / 'app-updates')
        moved = True
        for path in [RUNTIME / 'app-updates', *(RUNTIME / 'app-updates').iterdir()]:
            os.chown(path, owner.pw_uid, owner.pw_gid, follow_symlinks=False)
            path.chmod(0o755 if path.is_dir() else 0o644)
        PUBLIC.mkdir(mode=0o755)
        PUBLIC.chmod(0o755)
        (RUNTIME / 'current').symlink_to(RUNTIME / 'releases' / commit)
        reloaded = True
        run('/usr/bin/systemctl', 'daemon-reload')
        for service in SERVICES:
            run('/usr/bin/systemctl', 'restart', service)
        run('/usr/bin/curl', '--fail', '--silent', '--retry', '10', '--retry-connrefused', '--retry-delay',
            '1', '--max-time', '5', '--output', '/dev/null', 'http://127.0.0.1:18080/healthz')
        run('/usr/bin/curl', '--fail', '--silent', '--retry', '5', '--retry-delay', '1', '--max-time', '15',
            '--output', '/dev/null', server_url.rstrip('/') + '/app-updates/latest.json')
        created.append(RECEIPT)
        write_root(RECEIPT, json.dumps({'commit': commit, 'runtime': str(RUNTIME), 'backup': str(backup),
                                      'certificateSha256': manifest['certificateSha256']}).encode(), 0o600)
    except BaseException:
        if (RUNTIME / 'current').is_symlink():
            (RUNTIME / 'current').unlink()
        if moved:
            if PUBLIC.exists():
                PUBLIC.rmdir()
            shutil.move(RUNTIME / 'app-updates', PUBLIC)
            for name, (uid, gid, mode) in assets_info.items():
                path = PUBLIC if name == PUBLIC.name else PUBLIC / name
                os.chown(path, uid, gid, follow_symlinks=False)
                path.chmod(mode)
        for path in reversed(created):
            if path.exists():
                path.unlink()
        if acl_changed:
            run('/usr/bin/setfacl', f'--restore={backup}/workspace.acl')
        if reloaded:
            run('/usr/bin/systemctl', 'daemon-reload')
            for service in SERVICES:
                run('/usr/bin/systemctl', 'restart', service)
        raise
    print(f'Dashboard management migrated to coder. Backup: {backup}')
    print('Login database/private config preserved. Existing APKs moved without duplicates. Bot services untouched.')


def main():
    if os.geteuid() != 0 or len(sys.argv) != 3:
        raise ValueError('A human administrator runs this one-time migration with sudo, the reviewed commit and private HTTPS origin.')
    os.umask(0o077)
    BACKUPS.mkdir(mode=0o700, parents=True, exist_ok=True)
    if BACKUPS.is_symlink() or BACKUPS.stat().st_uid != 0 or BACKUPS.stat().st_mode & 0o077:
        raise ValueError('Expected private root-owned migration backup directory.')
    fd = os.open('/run/lock/trading-dashboard-migration.lock', os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        if os.fstat(fd).st_uid != 0 or os.fstat(fd).st_mode & 0o077:
            raise ValueError('Expected private root-owned migration lock.')
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        migrate(sys.argv[1], sys.argv[2])
    finally:
        os.close(fd)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        sys.exit(f'Dashboard migration failed: {message}')
