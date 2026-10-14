"""Parse units without installing, starting or contacting any service."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as directory:
    destination = Path(directory)
    for name in ('trading-dashboard.slice', 'trading-dashboard-api.service'):
        text = (root / 'deploy' / name).read_text()
        if name.endswith('.service'):
            # Only the installed executable does not exist on a CI/development host.
            text = text.replace('/opt/trading-dashboard/venv/bin/python', sys.executable)
        (destination / name).write_text(text)
    subprocess.run(['systemd-analyze', 'verify', '--man=no',
                    str(destination / 'trading-dashboard.slice'),
                    str(destination / 'trading-dashboard-api.service')], check=True)
print('Service/slice syntax validated; no services installed or started.')
