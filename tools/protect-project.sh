#!/usr/bin/env bash
# Encrypts a project folder for a PRIVATE GitHub repo. Usage: tools/protect-project.sh /path/to/project
# Creates <project>-protected/ with src.zip.enc + the project's .github/ workflow. Push that folder.
# Then add repo secrets SOURCE_PASSPHRASE (same passphrase) and optionally OUTPUT_PASSPHRASE.
set -euo pipefail
P="$(cd "${1:?project folder}" && pwd)"; OUT="$P-protected"
read -rsp "Source passphrase: " PW; echo
rm -rf "$OUT"; mkdir -p "$OUT"
( cd "$P" && zip -qr "$OUT/src.zip" . -x '.git/*' )
SOURCE_PASSPHRASE="$PW" openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:SOURCE_PASSPHRASE -in "$OUT/src.zip" -out "$OUT/src.zip.enc"
rm -f "$OUT/src.zip"; cp -r "$P/.github" "$OUT/"
echo "Done: $OUT"
echo "Decrypt a build output: openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in app.apk.enc -out app.apk"
