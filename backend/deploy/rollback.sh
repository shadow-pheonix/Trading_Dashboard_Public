#!/usr/bin/env bash
# Human-run dashboard rollback; preserves the current credential database and login keys.
set -euo pipefail
umask 077
fail() { echo "$1" >&2; exit 1; }
[[ ${EUID} -eq 0 ]] || fail 'A human administrator must run rollback with sudo.'
[[ $# -eq 1 && $1 =~ ^/var/backups/trading-dashboard/v2-[A-Za-z0-9]+$ ]] || fail 'Provide the backup path printed by update.sh.'
backup_path=$1
[[ -d $backup_path && ! -L $backup_path ]] || fail 'Backup directory missing or unsafe.'
for file in config.json Caddyfile INSTALLED_COMMIT; do
    [[ -f $backup_path/$file && ! -L $backup_path/$file ]] || fail 'Backup incomplete.'
done
[[ -d $backup_path/backend/dashboard_backend ]] || fail 'Backup code missing.'
work_path=$(mktemp -d /opt/trading-dashboard/.rollback-XXXXXXXX)
trap 'rm -rf -- "$work_path"' EXIT
cp -a "$backup_path/backend" "$work_path/backend"
/opt/trading-dashboard/https/caddy validate --config "$backup_path/Caddyfile" --adapter caddyfile >/dev/null 2>&1 || fail 'Backup proxy configuration validation failed.'
systemctl stop trading-dashboard-https.service trading-dashboard-api.service
if [[ -e /opt/trading-dashboard/backend ]]; then
    mv /opt/trading-dashboard/backend "$work_path/replaced-backend"
fi
mv "$work_path/backend" /opt/trading-dashboard/backend
install -o trading-dashboard -g trading-dashboard -m 600 "$backup_path/config.json" /var/lib/trading-dashboard/config.json
install -o root -g root -m 644 "$backup_path/Caddyfile" /etc/trading-dashboard/Caddyfile
install -o root -g root -m 644 "$backup_path/INSTALLED_COMMIT" /opt/trading-dashboard/INSTALLED_COMMIT
systemctl start trading-dashboard-api.service trading-dashboard-https.service
systemctl is-active --quiet trading-dashboard-api.service trading-dashboard-https.service
echo 'Dashboard rollback complete. Current login keys and credential database were preserved. Bot services were untouched.'
