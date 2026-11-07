#!/usr/bin/env bash
# Usage: ./protect.sh   -> makes ./protected-repo/ ready to push to a PRIVATE GitHub repo.
# It holds only the workflow + src.tar.gz.gpg (AES-256, your passphrase). No readable source.
set -euo pipefail
cd "$(dirname "$0")"
read -rsp "Source passphrase: " P; echo
OUT=protected-repo; rm -rf "$OUT"; mkdir -p "$OUT/.github/workflows"
tar --exclude=./protected-repo --exclude=./.git --exclude=./src.tar.gz -czf /tmp/src.tar.gz .
printf '%s' "$P" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 --symmetric --cipher-algo AES256 -o "$OUT/src.tar.gz.gpg" /tmp/src.tar.gz
rm -f /tmp/src.tar.gz
cp .github/workflows/build.yml "$OUT/.github/workflows/build.yml"
echo "Done. Push $OUT/ to a private repo, then add secrets SOURCE_PASSPHRASE (and APK_PASSPHRASE)."
