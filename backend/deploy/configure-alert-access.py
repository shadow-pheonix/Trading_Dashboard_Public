#!/usr/bin/env python3
"""HUMAN ROOT ONLY: prepare the three bot outboxes and grant read-only access.

Does not read environment files, change trade state, or control any service.
"""
import grp
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys

ROOTS = {name: Path('/home/hlmacdbot_user') / directory for name, directory in (
    ('main', 'HLTRADINGBOT'), ('vinay', 'HLTRADINGBOTVINAY'), ('madhu', 'HLTRADINGBOTMADHU'))}


def plan(value, roots=ROOTS):
    instances = value['instances']
    if len(instances) != 3 or {i['id'] for i in instances} != set(roots):
        raise ValueError()
    result = []
    for instance in instances:
        parent = roots[instance['id']]
        if parent.is_symlink() or parent.resolve() != parent:
            raise ValueError()
        path = parent / 'dashboard_alerts.sqlite'
        if instance.get('alertsPath') != str(path):
            raise ValueError()
        states = [Path(a['statePath']) for a in instance['accounts']]
        if not states or any(s.parent != parent or s.is_symlink() for s in states):
            raise ValueError()
        owners = {s.stat().st_uid for s in states}
        if len(owners) != 1:
            raise ValueError()
        if path.is_symlink() or (path.exists() and not stat.S_ISREG(path.stat().st_mode)):
            raise ValueError()
        result.append((path, owners.pop()))
    return result


def prepare(path, uid, gid):
    created = False
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        created = True
    except FileExistsError:
        if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode) or path.stat().st_uid != uid:
            raise ValueError()
    if created:
        db = sqlite3.connect(path)
        try:
            db.execute('CREATE TABLE alerts (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, timestamp INTEGER NOT NULL, message TEXT NOT NULL)')
            db.commit()
        finally:
            db.close()
    # Metadata only on an existing outbox; never clear or overwrite events.
    os.chown(path, uid, gid, follow_symlinks=False)
    os.chmod(path, 0o640, follow_symlinks=False)


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        sys.exit('A human administrator must run this helper with sudo and the private dashboard config path.')
    try:
        entries = plan(json.loads(Path(sys.argv[1]).read_bytes()))
        gid = grp.getgrnam('hlmacdbot_readers').gr_gid
        for path, uid in entries:
            prepare(path, uid, gid)
    except Exception:
        sys.exit('Alert reader setup failed. No trading state or service was changed. Check the private configuration and file metadata.')
    print('Three private bot outboxes prepared. Dashboard access is read-only. No environment files or trading state were read; no bot service was restarted.')


if __name__ == '__main__':
    main()
