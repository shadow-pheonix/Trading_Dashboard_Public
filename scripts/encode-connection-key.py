"""Wrap an existing dashboard access key without logging or replacing it."""
import argparse
import os
from pathlib import Path
import stat
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
from dashboard_backend.connection_keys import connection_key


def encode_file(source, output, server_url):
    # Only explicit key files are accepted; never discover credentials or parse env files.
    path = Path(source)
    if path.name.startswith('.env') or path.suffix != '.txt':
        raise ValueError('Expected a private dashboard key text file.')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'r') as file:
        info = os.fstat(file.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077 or info.st_size > 512:
            raise ValueError('Expected a private dashboard key text file.')
        token = file.read(512).strip()
    code = connection_key(server_url, token)
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'w') as file:
            file.write(code + '\n')
            file.flush()
            os.fsync(file.fileno())
    except BaseException:
        Path(output).unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, help='Existing private access-key .txt file.')
    parser.add_argument('--output', required=True, help='New private connection-key .txt file; must not exist.')
    parser.add_argument('--server-url', required=True, help='Private HTTPS origin; no path, query or credentials.')
    args = parser.parse_args()
    try:
        encode_file(args.input, args.output, args.server_url)
    except Exception:
        raise SystemExit('Connection key could not be saved. Check private settings and file permissions.')
    print('Connection key saved privately. The existing access key remains valid.')
