#!/usr/bin/env bash
# Re-sign an APK in place with the fixed key from the CI secrets, so CI
# builds install as updates over each other instead of only installing
# fresh (the runner's throwaway debug key changes on every run).
#
# Usage: sign-apk.sh <apk>
# Expects SIGNING_KEY (base64-encoded keystore), KEY_ALIAS,
# KEYSTORE_PASSWORD and KEY_PASSWORD in the environment; fails if any
# of them is missing.
set -euo pipefail

apk="${1:?usage: sign-apk.sh <apk>}"
keystore="$(mktemp --suffix=.keystore)"
trap 'rm -f "$keystore"' EXIT

for var in SIGNING_KEY KEY_ALIAS KEYSTORE_PASSWORD KEY_PASSWORD; do
    if [ -z "${!var:-}" ]; then
        echo "::error::$var is not set, cannot sign $apk"
        exit 1
    fi
done

printf '%s' "$SIGNING_KEY" | base64 -d > "$keystore"
apksigner="$(ls -d "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"/build-tools/*/apksigner | sort -V | tail -1)"
"$apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$apk.resigned" \
    "$apk"
mv "$apk.resigned" "$apk"
