#!/usr/bin/env python3
"""Coder-owned backend releases. Private config, login database and bots are untouched."""
import fcntl
import os
from pathlib import Path
import pwd
import re
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]
RUNTIME = Path('/home/coder/HLTRADINGBOT/.dashboard-runtime')
CONTROL = '/usr/local/sbin/trading-dashboard-control'


def git(*args):
    return subprocess.check_output(['/usr/bin/git', '-C', str(REPO), *args])


def verify_commit(commit):
    if not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('An exact reviewed commit is required.')
    if any(git('rev-parse', ref).decode().strip() != commit for ref in ('HEAD', 'origin/main')):
        raise ValueError('Reviewed commit must match clean HEAD and published origin/main.')
    if git('status', '--porcelain=v1', '--untracked-files=all').strip():
        raise ValueError('Checkout has local changes.')


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def prepare(commit):
    verify_commit(commit)
    destination = RUNTIME / 'releases' / commit
    if destination.exists() or destination.is_symlink():
        # An immutable prepared release is reused only after checking committed code.
        if destination.is_symlink() or (destination / 'COMMIT').read_text().strip() != commit:
            raise ValueError('Prepared release identity differs.')
        verify_release(destination, commit)
        return destination
    names = git('ls-tree', '-r', '--name-only', commit, '--', 'backend/dashboard_backend').decode().splitlines()
    if not names or any(not re.fullmatch(r'backend/dashboard_backend/[a-z_]+\.py', name) for name in names):
        raise ValueError('Unexpected backend release payload.')
    with tempfile.TemporaryDirectory(prefix='.prepare-', dir=RUNTIME / 'releases') as directory:
        stage = Path(directory) / 'release'
        package = stage / 'backend/dashboard_backend'
        package.mkdir(parents=True)
        for name in names:
            (stage / name).write_bytes(git('show', f'{commit}:{name}'))
        requirements = git('show', f'{commit}:backend/requirements.txt')
        (stage / 'requirements.txt').write_bytes(requirements)
        # Debian may omit ensurepip. The existing development pip installs only
        # into this new user-owned environment; it never changes system packages.
        run(sys.executable, '-m', 'venv', '--without-pip', stage / 'venv')
        run(sys.executable, '-m', 'pip', '--python', stage / 'venv/bin/python',
            'install', '--disable-pip-version-check',
            '-r', stage / 'requirements.txt')
        # Service-account traversal/readability; service mounts all code read-only.
        for path in [stage, *stage.rglob('*')]:
            if path.is_symlink():
                continue
            mode = 0o755 if path.is_dir() or os.access(path, os.X_OK) else 0o644
            path.chmod(mode)
        (stage / 'COMMIT').write_text(commit + '\n')
        (stage / 'COMMIT').chmod(0o644)
        os.rename(stage, destination)
    return destination


def verify_release(release, commit):
    names = git('ls-tree', '-r', '--name-only', commit, '--', 'backend/dashboard_backend').decode().splitlines()
    expected = {Path(name).name for name in names}
    package = release / 'backend/dashboard_backend'
    if {p.name for p in package.iterdir()} != expected:
        raise ValueError('Prepared backend payload differs from the reviewed commit.')
    for name in names:
        path = release / name
        if path.is_symlink() or path.read_bytes() != git('show', f'{commit}:{name}'):
            raise ValueError('Prepared backend code differs from the reviewed commit.')
    if (release / 'requirements.txt').read_bytes() != git('show', f'{commit}:backend/requirements.txt'):
        raise ValueError('Prepared dependencies differ from the reviewed commit.')


def set_current(release):
    if release.parent != RUNTIME / 'releases' or release.is_symlink() or release.resolve() != release:
        raise ValueError('Unsafe backend release.')
    temporary = RUNTIME / '.current-next'
    if temporary.exists() or temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(release)
    os.replace(temporary, RUNTIME / 'current')


def healthy():
    return subprocess.run(['/usr/bin/curl', '--fail', '--silent', '--max-time', '5', '--output',
                           '/dev/null', 'http://127.0.0.1:18080/healthz']).returncode == 0


def restart_and_verify():
    run('/usr/bin/sudo', '-n', CONTROL, 'restart-api')
    run('/usr/bin/curl', '--fail', '--silent', '--retry', '10', '--retry-connrefused', '--retry-delay',
        '1', '--max-time', '5', '--output', '/dev/null', 'http://127.0.0.1:18080/healthz')


def deploy(commit):
    candidate = prepare(commit)
    run('/usr/bin/sudo', '-n', CONTROL, 'check', commit)
    current = (RUNTIME / 'current').resolve(strict=True)
    if current == candidate and healthy():
        print('Reviewed backend already active and healthy; no restart.')
        return
    set_current(candidate)
    try:
        restart_and_verify()
    except BaseException:
        set_current(current)
        restart_and_verify()
        raise
    previous = RUNTIME / 'previous'
    if previous.exists() or previous.is_symlink():
        previous.unlink()
    previous.symlink_to(current)
    print(f'Dashboard backend deployed: {commit}. Login database and private config preserved.')


def rollback():
    previous = (RUNTIME / 'previous').resolve(strict=True)
    current = (RUNTIME / 'current').resolve(strict=True)
    if previous == current:
        raise ValueError('No earlier backend release recorded.')
    set_current(previous)
    try:
        restart_and_verify()
    except BaseException:
        set_current(current)
        restart_and_verify()
        raise
    (RUNTIME / 'previous').unlink()
    (RUNTIME / 'previous').symlink_to(current)
    print('Dashboard backend rolled back. Current login keys were preserved.')


def main():
    if pwd.getpwuid(os.geteuid()).pw_name != 'coder':
        raise ValueError('Run as coder; no root backend deployment.')
    os.umask(0o022)
    if not RUNTIME.is_dir() or RUNTIME.is_symlink() or RUNTIME.stat().st_uid != os.geteuid():
        raise ValueError('Complete the one-time human migration first.')
    lock_path = RUNTIME / '.deploy.lock'
    fd = os.open(lock_path, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if len(sys.argv) == 3 and sys.argv[1] in ('prepare', 'deploy'):
            {'prepare': prepare, 'deploy': deploy}[sys.argv[1]](sys.argv[2])
        elif sys.argv[1:] == ['rollback']:
            rollback()
        else:
            raise ValueError('Use prepare COMMIT, deploy COMMIT, or rollback.')
    finally:
        os.close(fd)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        sys.exit(f'Dashboard management failed: {message}')
