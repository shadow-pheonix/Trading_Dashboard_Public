#!/usr/bin/env bash
# Run once on a trusted computer with Java 17, Python 3 and authenticated GitHub CLI.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/.." && pwd)
signing_dir=${DASHBOARD_SIGNING_DIR:-$root/.signing}
command -v keytool >/dev/null || { echo 'Install Java 17 (includes keytool).'; exit 1; }
command -v gh >/dev/null || { echo 'Install GitHub CLI and authenticate for this repository.'; exit 1; }
command -v python3 >/dev/null || { echo 'Install Python 3.'; exit 1; }
gh auth status >/dev/null 2>&1 || { echo 'Authenticate GitHub CLI before continuing.'; exit 1; }
repo=${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
keystore="$signing_dir/dashboard-signing.p12"
password_file="$signing_dir/store-password.txt"

if [ -e "$keystore" ]; then
  test -s "$password_file" || { echo 'Keystore exists but its password file is missing. Restore the original password; do not generate a replacement.'; exit 1; }
  echo 'Reusing the existing signing key.'
else
  existing_secrets=$(gh secret list --repo "$repo" --json name --jq '.[].name')
  if printf '%s\n' "$existing_secrets" | grep -Eq '^ANDROID_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|SIGNING_CERT_SHA256)$'; then
    echo 'Signing is already configured on GitHub. Restore your original .signing backup; a replacement key would break app updates.'
    exit 1
  fi
  test ! -e "$password_file" || { echo 'Password file exists without a keystore. Restore your keystore backup before proceeding.'; exit 1; }
  # Machine-generated credentials are passed through files, never printed or put in process arguments.
  python3 - "$password_file" <<'PY'
import secrets, sys
from pathlib import Path
Path(sys.argv[1]).write_text(secrets.token_urlsafe(48))
PY
  keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias dashboard \
    -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=Trading Dashboard, OU=Android, O=Trading Dashboard' \
    -storepass:file "$password_file" -keypass:file "$password_file" >/dev/null
fi

fingerprint=$(keytool -exportcert -alias dashboard -keystore "$keystore" -storetype PKCS12 \
  -storepass:file "$password_file" | sha256sum | cut -d ' ' -f 1)
python3 - "$keystore" "$signing_dir/keystore.base64" <<'PY'
import base64, sys
from pathlib import Path
Path(sys.argv[2]).write_bytes(base64.b64encode(Path(sys.argv[1]).read_bytes()))
PY
# Upload the fingerprint last so partially configured signing cannot produce an APK.
gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo" < "$signing_dir/keystore.base64"
gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo" < "$password_file"
printf '%s' "$fingerprint" | gh secret set ANDROID_SIGNING_CERT_SHA256 --repo "$repo"
rm -f "$signing_dir/keystore.base64"
printf 'Permanent signing configured. Certificate SHA-256: %s\n' "$fingerprint"
echo 'Back up the .signing directory in a secure password manager or encrypted backup.'
echo 'Keep this key for all future updates. Never commit .signing or the keystore.'
gh workflow run android.yml --repo "$repo" --ref main
