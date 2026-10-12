#!/usr/bin/env bash
set -euo pipefail
umask 077

for name in ANDROID_RELEASE_KEYSTORE_BASE64 ANDROID_RELEASE_STORE_PASSWORD ANDROID_RELEASE_KEY_ALIAS ANDROID_RELEASE_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "::error::$name is missing"
    exit 1
  fi
done
[[ "$ANDROID_RELEASE_KEY_ALIAS" == 'nextvol-release' ]]

temporary=$(mktemp -d "$RUNNER_TEMP/nextvol-release.XXXXXX")
trap 'rm -rf "$temporary"' EXIT
printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode > "$temporary/release.jks"

unsigned_apk=$(python3 - <<'PY'
import json, os
from pathlib import Path

directory = Path('app/build/outputs/apk/release')
metadata = json.loads((directory / 'output-metadata.json').read_text())
assert metadata['applicationId'] == 'indi.renakoni.nextvol', 'Wrong application ID'
assert metadata['variantName'] == 'release', 'Wrong build variant'
assert len(metadata['elements']) == 1, 'Expected one universal APK'
element = metadata['elements'][0]
assert not element['filters'], 'APK must not be ABI-specific'
assert element['versionName'] == os.environ['APP_VERSION'], 'Version name mismatch'
assert element['versionCode'] == int(os.environ['APP_VERSION_CODE']), 'Version code mismatch'
apk = directory / element['outputFile']
assert apk.is_file() and apk.suffix == '.apk', 'Missing APK'
print(apk)
PY
)

tools="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p release-artifacts
apk="release-artifacts/NextVol-$APP_VERSION-release.apk"
"$tools/zipalign" -P 16 -f 4 "$unsigned_apk" "$temporary/aligned.apk"
"$tools/apksigner" sign --ks "$temporary/release.jks" \
  --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" \
  --ks-pass env:ANDROID_RELEASE_STORE_PASSWORD --key-pass env:ANDROID_RELEASE_KEY_PASSWORD \
  --v4-signing-enabled false \
  --out "$apk" "$temporary/aligned.apk"
"$tools/zipalign" -c -P 16 4 "$apk"
"$tools/apksigner" verify --verbose --print-certs "$apk" > "$temporary/signature.txt"
cat "$temporary/signature.txt"
grep -Fx 'Signer #1 certificate SHA-256 digest: 87b61dcf3eecca2b93acb8aae035ce016260508e09e205266912703523efacfc' "$temporary/signature.txt"

"$tools/aapt2" dump badging "$apk" > "$temporary/badging.txt"
grep -F "package: name='indi.renakoni.nextvol' versionCode='$APP_VERSION_CODE' versionName='$APP_VERSION'" "$temporary/badging.txt"
grep -Fx "sdkVersion:'24'" "$temporary/badging.txt"
if grep -q '^application-debuggable' "$temporary/badging.txt"; then
  echo '::error::Release APK is debuggable'
  exit 1
fi

(
  cd release-artifacts
  sha256sum "NextVol-$APP_VERSION-release.apk" > SHA256SUMS
  sha256sum --check SHA256SUMS
)
