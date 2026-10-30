#!/usr/bin/env bash
# Keeps the version and "last edited" block in README.md up to date.
#
#   bash tools/stamp.sh                      refresh the block (version comes from app/build.gradle)
#   bash tools/stamp.sh "what I changed"     also add a line to the edit history
#   bash tools/stamp.sh --bump "what I changed"   raise versionName (x.y.Z+1) and versionCode first
set -euo pipefail
cd "$(dirname "$0")/.."
G=app/build.gradle
R=README.md
[ -f "$G" ] && [ -f "$R" ] || { echo "Run this inside the CloudIDE project (README.md and app/build.gradle needed)"; exit 1; }

BUMP=0
if [ "${1:-}" = "--bump" ]; then BUMP=1; shift; fi
NOTE="${1:-}"

VC=$(sed -n "s/.*versionCode *= *\([0-9][0-9]*\).*/\1/p" "$G" | head -n 1)
VN=$(sed -n "s/.*versionName *= *['\"]\([^'\"]*\)['\"].*/\1/p" "$G" | head -n 1)
[ -n "$VC" ] && [ -n "$VN" ] || { echo "Could not read versionCode/versionName from $G"; exit 1; }

if [ "$BUMP" = 1 ]; then
  IFS=. read -r MA MI PA <<<"$VN"
  VN="$MA.$MI.$(( ${PA:-0} + 1 ))"
  VC=$(( VC + 1 ))
  sed -i -E "s/(versionCode *= *)[0-9]+/\1$VC/; s/(versionName *= *['\"])[^'\"]+/\1$VN/" "$G"
fi

NOW=$(date '+%Y-%m-%d %H:%M (%Z, UTC%z)')
BLOCK=$(mktemp)
{
  echo "<!-- STAMP:START -->"
  echo "| | |"
  echo "|---|---|"
  echo "| **Current version** | $VN (versionCode $VC) |"
  echo "| **Last edited** | $NOW |"
  echo "<!-- STAMP:END -->"
} > "$BLOCK"

# replace the old block
awk -v blk="$BLOCK" '
  /<!-- STAMP:START -->/ { while ((getline line < blk) > 0) print line; skip = 1; next }
  /<!-- STAMP:END -->/   { skip = 0; next }
  !skip { print }
' "$R" > "$R.new" && mv "$R.new" "$R"
rm -f "$BLOCK"

# newest entry goes right under the HISTORY marker
if [ -n "$NOTE" ]; then
  LINE="- $(date '+%Y-%m-%d %H:%M') - v$VN - $NOTE"
  ESC=$(printf '%s' "$LINE" | sed 's/[\/&]/\\&/g')
  sed -i "/<!-- HISTORY -->/a $ESC" "$R"
fi

# the README shows the current tools/termux-push.sh, so it can always be re-created by hand if the zip is damaged
if [ -f tools/termux-push.sh ] && grep -q "<!-- SCRIPT:START -->" "$R"; then
  SB=$(mktemp)
  { echo "<!-- SCRIPT:START -->"; echo '```bash'; cat tools/termux-push.sh; echo '```'; echo "<!-- SCRIPT:END -->"; } > "$SB"
  awk -v blk="$SB" '
    /<!-- SCRIPT:START -->/ { while ((getline line < blk) > 0) print line; skip = 1; next }
    /<!-- SCRIPT:END -->/   { skip = 0; next }
    !skip { print }
  ' "$R" > "$R.new" && mv "$R.new" "$R"
  rm -f "$SB"
fi

echo "README stamped: v$VN (code $VC), $NOW"
