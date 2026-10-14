#!/usr/bin/env bash
# Human-run dashboard-only upgrade. Root script is prepared for review, never run by AI.
set -euo pipefail
umask 077
fail() { echo "$1" >&2; exit 1; }
[[ ${EUID} -eq 0 ]] || fail 'A human administrator must run this update with sudo.'
[[ $# -eq 1 && $1 =~ ^[a-f0-9]{40}$ ]] || fail 'Provide the exact reviewed dashboard commit SHA.'
reviewed_commit=$1
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
[[ $(git -C "$source_root" rev-parse --show-toplevel) == "$source_root" ]] || fail 'Use a real dashboard checkout.'
[[ $(git -C "$source_root" rev-parse HEAD) == "$reviewed_commit" ]] || fail 'Checkout differs from the reviewed commit.'
git -C "$source_root" diff --quiet HEAD -- backend || fail 'Backend checkout has local changes.'
[[ -d /opt/trading-dashboard/backend && -f /var/lib/trading-dashboard/config.json ]] || fail 'Existing dashboard installation required.'
[[ -f /etc/trading-dashboard/Caddyfile && -x /opt/trading-dashboard/https/caddy ]] || fail 'Existing dashboard HTTPS proxy required.'
[[ -f /var/lib/trading-dashboard/dashboard.sqlite ]] || fail 'Existing credential database required; never recreate keys.'
# The upgrade needs no package installation or venv change.
cmp /opt/trading-dashboard/requirements.txt <(git -C "$source_root" show "$reviewed_commit:backend/requirements.txt") || fail 'Dependency change requires a separately reviewed update.'
install -d -m 700 /var/backups/trading-dashboard
backup_path=$(mktemp -d /var/backups/trading-dashboard/v2-XXXXXXXX)
work_path=$(mktemp -d /opt/trading-dashboard/.update-XXXXXXXX)
mutated=0
snapshot_path=''
rollback() {
    result=$?
    trap - EXIT
    if (( result != 0 && mutated )); then
        echo 'Update failed; restoring the dashboard backup.' >&2
        bash "$source_root/backend/deploy/rollback.sh" "$backup_path" || echo 'Automatic dashboard rollback failed; use the documented manual rollback.' >&2
    fi
    if [[ -n $snapshot_path ]]; then rm -f -- "$snapshot_path"; fi
    rm -rf -- "$work_path"
    exit "$result"
}
trap rollback EXIT
# Name both directories: install creates unnamed parents as 0700 under umask 077.
install -d -m 755 "$work_path/backend" "$work_path/backend/dashboard_backend"
modules=(__init__.py __main__.py api.py alerts.py balances.py config.py files.py grouping.py market.py storage.py validation.py)
for module in "${modules[@]}"; do
    git -C "$source_root" show "$reviewed_commit:backend/dashboard_backend/$module" > "$work_path/backend/dashboard_backend/$module"
    chmod 644 "$work_path/backend/dashboard_backend/$module"
done
git -C "$source_root" show "$reviewed_commit:backend/deploy/Caddyfile" > "$work_path/Caddyfile"
git -C "$source_root" show "$reviewed_commit:backend/deploy/configure-balances.py" > "$work_path/configure-balances.py"
# Root-owned, private, transient importer; it is never installed into the API service.
chmod 600 "$work_path/configure-balances.py"
PYTHONPATH="$work_path/backend" /opt/trading-dashboard/venv/bin/python "$work_path/configure-balances.py" \
    /var/lib/trading-dashboard/config.json "$work_path/config.json"
# The service may traverse this private group directory and read its candidate config.
# Env files and the root-only importer remain inaccessible to the service.
chgrp trading-dashboard "$work_path" "$work_path/config.json"
chmod 710 "$work_path"
chmod 640 "$work_path/config.json"
git -C "$source_root" show "$reviewed_commit:backend/deploy/check-startup.py" > "$work_path/check-startup.py"
chmod 644 "$work_path/check-startup.py"
runuser -u trading-dashboard -- /opt/trading-dashboard/venv/bin/python -B "$work_path/check-startup.py" \
    "$work_path/backend" "$work_path/config.json" /var/lib/trading-dashboard/dashboard.sqlite
/opt/trading-dashboard/https/caddy validate --config "$work_path/Caddyfile" --adapter caddyfile >/dev/null 2>&1 || fail 'Proxy configuration validation failed.'
cp -a /opt/trading-dashboard/backend "$backup_path/backend"
cp -a /opt/trading-dashboard/INSTALLED_COMMIT "$backup_path/INSTALLED_COMMIT"
cp -a /var/lib/trading-dashboard/config.json "$backup_path/config.json"
cp -a /etc/trading-dashboard/Caddyfile "$backup_path/Caddyfile"
# Run SQLite as its owner, preventing root-owned WAL/SHM sidecars from breaking logins.
snapshot_path=$(mktemp /var/lib/trading-dashboard/.db-backup-XXXXXXXX)
chown trading-dashboard:trading-dashboard "$snapshot_path"
runuser -u trading-dashboard -- /opt/trading-dashboard/venv/bin/python - "$snapshot_path" <<'PYSQL'
import sqlite3,sys
from contextlib import closing
with closing(sqlite3.connect('file:/var/lib/trading-dashboard/dashboard.sqlite?mode=ro',uri=True)) as source:
    with closing(sqlite3.connect(sys.argv[1])) as target:
        source.backup(target)
PYSQL
install -o root -g root -m 600 "$snapshot_path" "$backup_path/dashboard.sqlite"
rm -f -- "$snapshot_path"
snapshot_path=''
cp -a /var/lib/trading-dashboard/keys "$backup_path/keys"
printf '%s\n' "$reviewed_commit" > "$backup_path/UPDATED_TO"
echo "Dashboard backup: $backup_path"
mutated=1
systemctl stop trading-dashboard-https.service trading-dashboard-api.service
mv /opt/trading-dashboard/backend "$work_path/previous-backend"
mv "$work_path/backend" /opt/trading-dashboard/backend
install -o trading-dashboard -g trading-dashboard -m 600 "$work_path/config.json" /var/lib/trading-dashboard/config.json
install -o root -g root -m 644 "$work_path/Caddyfile" /etc/trading-dashboard/Caddyfile
printf '%s\n' "$reviewed_commit" > /opt/trading-dashboard/INSTALLED_COMMIT
chmod 644 /opt/trading-dashboard/INSTALLED_COMMIT
systemctl start trading-dashboard-api.service trading-dashboard-https.service
systemctl is-active --quiet trading-dashboard-api.service trading-dashboard-https.service
curl --fail --silent --show-error --retry 5 --retry-connrefused --retry-delay 1 http://127.0.0.1:18080/healthz >/dev/null
echo 'Dashboard update complete. Login keys were preserved. Test the APK and authenticated v1/v2 endpoints.'
echo "Rollback: sudo bash $source_root/backend/deploy/rollback.sh $backup_path"
