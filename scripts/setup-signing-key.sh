#!/usr/bin/env bash
# Create the stable candidate signing key once, and hand it to GitHub as two secrets.
#
# Why this exists
# ---------------
# Every device candidate was signed with the runner's own debug keystore. Android generates that
# keystore per machine, so each CI run produced a differently signed APK, and Android refuses to
# install an update whose signature does not match the installed app. The only way forward was to
# uninstall first — which takes the settings and the stored API key with it.
#
# One key, kept as a repository secret, fixes that permanently: every future candidate installs
# straight over the previous one.
#
# The key never touches the repository. It is generated here, uploaded as a secret, and the local
# copy is left in signing/ which is gitignored. Keep that copy — it is the only way to keep
# publishing updates that install over what people already have.
#
# Usage:  scripts/setup-signing-key.sh
# Needs:  keytool (ships with any JDK) and gh, signed in with repo admin rights.
set -euo pipefail

repo="${VISIONBRIDGE_REPO:-bellinghamfolks-debug/cautious-octo-eureka}"
dir="$(cd "$(dirname "$0")/.." && pwd)"
keystore="$dir/signing/visionbridge-signing-v1.jks"

command -v keytool >/dev/null || { echo "keytool not found — install a JDK first." >&2; exit 1; }
command -v gh >/dev/null || { echo "gh not found — install the GitHub CLI and run 'gh auth login'." >&2; exit 1; }

if [ -f "$keystore" ]; then
  echo "Using the key that already exists at $keystore"
  echo "Enter its password when asked."
  read -r -s -p "Keystore password: " password; echo
else
  mkdir -p "$dir/signing"
  # Generated rather than asked for, so the password is not one a person has to invent, remember,
  # or accidentally reuse from somewhere that matters.
  password="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 28)"
  echo "Creating a new candidate signing key at $keystore"
  keytool -genkeypair -v \
    -keystore "$keystore" \
    -storetype JKS \
    -storepass "$password" \
    -keypass "$password" \
    -alias visionbridge \
    -keyalg RSA -keysize 4096 \
    -validity 10950 \
    -dname "CN=VisionBridge device candidate, OU=VisionBridge, O=VisionBridge, C=SA" >/dev/null
fi

fingerprint="$(keytool -list -v -keystore "$keystore" -storepass "$password" -alias visionbridge \
  | sed -n 's/.*SHA256: \(.*\)/\1/p' | head -n1)"

gh secret set VISIONBRIDGE_KEYSTORE_BASE64 --repo "$repo" --body "$(base64 -w0 "$keystore" 2>/dev/null || base64 "$keystore" | tr -d '\n')"
gh secret set VISIONBRIDGE_KEYSTORE_PASSWORD --repo "$repo" --body "$password"

cat <<EOF

Done. Both secrets are set on $repo.

Certificate SHA-256: $fingerprint

Every candidate built from now on carries this certificate and installs straight over the previous
one. The build before this change was signed with a throwaway key, so that one upgrade still needs
an uninstall first; after it, updates go on top.

Keep $keystore. Losing it means a new certificate, and a new certificate means uninstalling again.
EOF
