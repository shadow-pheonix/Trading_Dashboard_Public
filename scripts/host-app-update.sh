#!/usr/bin/env bash
# Authenticated GitHub download; coder publishes public assets after the one-time migration.
# Legacy root publication remains human-only; migrated publication runs as coder.
set -euo pipefail
[[ $# -eq 1 && $1 =~ ^v0\.1\.[1-9][0-9]*$ ]] || { echo 'Usage: bash scripts/host-app-update.sh v0.1.VERSION' >&2; exit 1; }
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
command -v gh >/dev/null || { echo 'Install GitHub CLI and run gh auth login as your normal user first.' >&2; exit 1; }
download_root=$(mktemp -d)
trap 'rm -rf -- "$download_root"' EXIT
gh release download "$1" --repo shadow-pheonix/Trading_Dashboard_Public --dir "$download_root" \
    --pattern "Trading-Dashboard-${1#v}.apk" --pattern SHA256SUMS.txt \
    --pattern signature.txt --pattern build-info.txt --pattern latest.json
python3 - "$download_root/latest.json" "$1" <<'PY'
import json,sys
value=json.load(open(sys.argv[1]))
assert 'v'+value['versionName']==sys.argv[2]
PY
managed_assets=/home/coder/HLTRADINGBOT/.dashboard-runtime/app-updates
if [[ ${EUID} -ne 0 && -d $managed_assets && -O $managed_assets ]]; then
    python3 "$source_root/backend/deploy/publish-app-update.py" --managed "$download_root"
elif [[ ${EUID} -eq 0 ]]; then
    python3 "$source_root/backend/deploy/publish-app-update.py" "$download_root"
else
    sudo python3 "$source_root/backend/deploy/publish-app-update.py" "$download_root"
fi
# Download scratch space is removed on success and failure; only three hosted APKs remain.
