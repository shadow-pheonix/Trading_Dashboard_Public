#!/usr/bin/env bash
# First installation only. Human-run; creates dashboard-owned files and never starts a service.
set -euo pipefail
umask 077
fail() { echo "$1" >&2; exit 1; }
[[ ${EUID} -eq 0 ]] || fail 'Run this installer on the VPS as a human administrator with sudo.'
[[ $# -eq 1 && $1 =~ ^[a-f0-9]{40}$ ]] || fail 'Provide the exact reviewed Git commit SHA.'
expected_commit=$1
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
[[ $(git -C "$source_root" rev-parse --show-toplevel) == "$source_root" ]] || fail 'Use a real Trading_Dashboard Git checkout.'
[[ $(git -C "$source_root" rev-parse HEAD) == "$expected_commit" ]] || fail 'Checkout does not match the reviewed commit.'
git -C "$source_root" diff --quiet HEAD -- backend || fail 'Backend tracked files have local changes.'
[[ $(cat /proc/1/comm) == systemd ]] || fail 'The target must be a systemd VPS.'
for destination in /opt/trading-dashboard /var/lib/trading-dashboard /usr/local/sbin/trading-dashboard-admin \
  /etc/systemd/system/trading-dashboard-api.service \
  /etc/systemd/system/trading-dashboard.slice; do
  [[ ! -e $destination && ! -L $destination ]] || fail 'Dashboard is already or partially installed; do not overwrite it.'
done
if getent passwd trading-dashboard >/dev/null || getent group trading-dashboard >/dev/null; then
  fail 'Dashboard account/group already exists; inspect it before installing.'
fi
for tool in python3 useradd runuser install systemd-analyze systemctl; do
  command -v "$tool" >/dev/null || fail 'A required tool is missing; install prerequisites manually.'
done
python3 -c 'import sys; assert sys.version_info >= (3,12); import venv' || fail 'Python 3.12+ with venv is required.'
for unit in trading-dashboard.slice trading-dashboard-api.service; do
  [[ -f "$source_root/backend/deploy/$unit" && ! -L "$source_root/backend/deploy/$unit" ]] || fail 'Invalid unit source.'
done
getent group hlmacdbot_readers >/dev/null || fail 'Existing read-only hlmacdbot_readers group is required. Do not change bot file permissions.'
# Installation uses only committed backend files, never untracked secrets, keys, or bot data.
useradd --system --user-group --home-dir /var/lib/trading-dashboard --shell /usr/sbin/nologin trading-dashboard
install -d -m 755 /opt/trading-dashboard /opt/trading-dashboard/backend /opt/trading-dashboard/backend/dashboard_backend
install -d -o trading-dashboard -g trading-dashboard -m 700 /var/lib/trading-dashboard /var/lib/trading-dashboard/keys
modules=(__init__.py __main__.py api.py alerts.py balances.py config.py files.py grouping.py market.py storage.py validation.py)
for module in "${modules[@]}"; do
  git -C "$source_root" show "$expected_commit:backend/dashboard_backend/$module" \
    > "/opt/trading-dashboard/backend/dashboard_backend/$module"
  chmod 644 "/opt/trading-dashboard/backend/dashboard_backend/$module"
done
git -C "$source_root" show "$expected_commit:backend/requirements.txt" > /opt/trading-dashboard/requirements.txt
chmod 644 /opt/trading-dashboard/requirements.txt
python3 -m venv /opt/trading-dashboard/venv
/opt/trading-dashboard/venv/bin/python -m pip install -r /opt/trading-dashboard/requirements.txt
# Keep code root-owned while allowing only its service group to read and execute it.
# With the private installation umask, venv otherwise creates root-only directories.
chgrp -R trading-dashboard /opt/trading-dashboard/venv
chmod -R g+rX /opt/trading-dashboard/venv
git -C "$source_root" show "$expected_commit:backend/config.example.json" > /var/lib/trading-dashboard/config.json
chown trading-dashboard:trading-dashboard /var/lib/trading-dashboard/config.json
chmod 600 /var/lib/trading-dashboard/config.json
for unit in trading-dashboard.slice trading-dashboard-api.service; do
  git -C "$source_root" show "$expected_commit:backend/deploy/$unit" > "/etc/systemd/system/$unit"
  chmod 644 "/etc/systemd/system/$unit"
done
git -C "$source_root" show "$expected_commit:backend/deploy/trading-dashboard-admin" > /usr/local/sbin/trading-dashboard-admin
chmod 755 /usr/local/sbin/trading-dashboard-admin
printf '%s\n' "$expected_commit" > /opt/trading-dashboard/INSTALLED_COMMIT
chmod 644 /opt/trading-dashboard/INSTALLED_COMMIT
systemctl daemon-reload
echo 'Dashboard files installed. No services were started or restarted.'
echo 'Privately configure /var/lib/trading-dashboard/config.json, initialize the dashboard database and create keys before starting the dashboard service.'
