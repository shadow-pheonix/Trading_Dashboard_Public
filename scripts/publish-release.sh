#!/usr/bin/env bash
# Called only after the workflow verifies the permanently signed APK.
set -euo pipefail
fail() { echo "$1" >&2; exit 1; }
[[ ${GITHUB_REF:-} == refs/heads/main ]] || fail 'Only main builds can publish APK releases.'
[[ ${APP_VERSION_CODE:-} =~ ^[1-9][0-9]*$ && ${APP_VERSION_NAME:-} == "0.1.${APP_VERSION_CODE}" ]] || fail 'Invalid app release version.'
[[ ${GITHUB_SHA:-} =~ ^[a-f0-9]{40}$ ]] || fail 'Missing source commit.'
[[ ${GITHUB_REPOSITORY:-} =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail 'Missing repository.'
[[ -d ${RUNNER_TEMP:-} && -n ${GITHUB_STEP_SUMMARY:-} ]] || fail 'Missing runner paths.'
release_tag="v${APP_VERSION_NAME}"
assets=("dist/Trading-Dashboard-${APP_VERSION_NAME}.apk" dist/SHA256SUMS.txt dist/signature.txt dist/build-info.txt dist/latest.json)
for asset in "${assets[@]}"; do
  [[ -f $asset && -s $asset && ! -L $asset ]] || fail 'A verified release file is missing.'
done
grep -Fxq "Version: ${APP_VERSION_NAME}" dist/build-info.txt || fail 'APK version metadata differs.'
grep -Fxq "Version code: ${APP_VERSION_CODE}" dist/build-info.txt || fail 'APK version code differs.'
grep -Fxq "Commit: ${GITHUB_SHA}" dist/build-info.txt || fail 'APK source metadata differs.'
(cd dist && sha256sum --check SHA256SUMS.txt)
python3 "$(dirname -- "${BASH_SOURCE[0]}")/update-manifest.py" dist
# Never move an existing version tag. If lookup fails, creation must succeed;
# an existing tag or lack of permission makes that creation fail safely.
if tag_ref=$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${release_tag}" --jq '.object.type + " " + .object.sha' 2>/dev/null); then
  [[ $tag_ref == "commit ${GITHUB_SHA}" ]] || fail 'This version tag belongs to a different commit.'
else
  gh api --method POST "repos/${GITHUB_REPOSITORY}/git/refs" \
    -f "ref=refs/tags/${release_tag}" -f "sha=${GITHUB_SHA}" >/dev/null
fi
notes_file="${RUNNER_TEMP}/dashboard-release-notes.md"
trap 'rm -f "$notes_file"' EXIT
cat > "$notes_file" <<'NOTES'
Download **Trading-Dashboard-VERSION.apk** under Assets and install it on Android 8.0 or later.
This APK uses the same permanent signing certificate and application ID as previous releases, so newer versions can update the installed app.

This is a read-only dashboard. Configure the separate backend and use your instance dashboard key to connect. Backend setup: https://github.com/shadow-pheonix/Trading_Dashboard/blob/main/backend/README.md

The assets include SHA256SUMS.txt, signature.txt, build-info.txt and latest.json for checksum, certificate and version verification. APK signing credentials and login keys are never included. The VPS publisher makes only the APK and update manifest public; it retains three APK versions. After one-time human permission setup, coder can manage hosting. See docs/UPDATE.md for hosting setup.
NOTES
sed -i "s/Trading-Dashboard-VERSION.apk/Trading-Dashboard-${APP_VERSION_NAME}.apk/" "$notes_file"
if gh release view "$release_tag" --repo "$GITHUB_REPOSITORY" --json tagName >/dev/null 2>&1; then
  # A rerun has the same version/commit; it can restore assets without moving the tag.
  gh release upload "$release_tag" "${assets[@]}" --repo "$GITHUB_REPOSITORY" --clobber
else
  gh release create "$release_tag" "${assets[@]}" --repo "$GITHUB_REPOSITORY" \
    --verify-tag --title "Trading Dashboard ${APP_VERSION_NAME}" --notes-file "$notes_file" --latest
fi
printf 'Download version %s: https://github.com/%s/releases/tag/%s\n' \
  "$APP_VERSION_NAME" "$GITHUB_REPOSITORY" "$release_tag" >> "$GITHUB_STEP_SUMMARY"
