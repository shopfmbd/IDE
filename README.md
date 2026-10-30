# CloudIDE

An Android IDE that edits, builds and runs projects on the phone (Java, Kotlin, Compose, C++, Rust, Flutter and more).

<!-- STAMP:START -->
| | |
|---|---|
| **Current version** | 4.5.10 (versionCode 26) |
| **Last edited** | 2026-10-31 03:02 (+06, UTC+0600) |
<!-- STAMP:END -->

## RULE: ALWAYS UPDATE THIS FILE

Every time anything in this project changes, this README must be updated in the same step:

1. Raise the version and write what you changed:
   `bash tools/stamp.sh --bump "short note about the change"`
2. That updates `versionName`/`versionCode` in `app/build.gradle`, the version and "Last edited" lines above, and the history below.
3. Add the details to `CHANGELOG.md`.
4. Back up the FULL project to INTERNAL storage (see "Backups" below). Never rely on Termux alone.
5. Give every zip a new name, never reuse one: `IDE-v<version>-<yyyymmdd>-<hhmm>.zip`
   (for example `IDE-v4.5.2-20261028-0420.zip`). A reused name makes Android save "(1)" copies and you may apply an old file by mistake.

## Apply an update in Termux

Copy the new zip to the phone's Download folder, then run:

```
apt-get install -y git zip unzip && Z=$(ls -t ~/storage/shared/Download/IDE-v*.zip | head -n 1) && bash <(unzip -p "$Z" IDE-main/tools/termux-push.sh) "$Z"
```

It picks the newest zip, backs the project up, commits and pushes. It signs you in through the browser (GitHub CLI shows a code, you approve it at github.com/login/device). Only if the CLI cannot be installed does it ask for a token, which it does not save.

## Backup and push script (tools/termux-push.sh)

This is the script the apply command runs. It does everything in order: checks the zip is not damaged, backs up the current
project to internal storage, unpacks the new version into `~/IDE`, refreshes the README stamp, commits, backs up again, and pushes
to your GitHub repository. The account and repository are not stored in the zip: the script reads them from your existing working copy (its `origin` remote) or your GitHub CLI login, and only asks if neither is known. The zip contains no `.git` folder, so your own history and remote are never replaced.

**Command (copy the zip to the Download folder first; it uses the newest `IDE-v*.zip`):**

```
apt-get install -y git zip unzip && Z=$(ls -t ~/storage/shared/Download/IDE-v*.zip | head -n 1) && bash <(unzip -p "$Z" IDE-main/tools/termux-push.sh) "$Z"
```

**If the zip is damaged and the command cannot read the script**, re-create it by hand from the copy below:
run `nano ~/termux-push.sh`, paste the whole script, save (Ctrl+X, Y, Enter), then run `bash ~/termux-push.sh`.
Check a zip before using it with `unzip -tq ~/storage/shared/Download/<zip name>`.

The block below is refreshed automatically from `tools/termux-push.sh` by `tools/stamp.sh`.

<!-- SCRIPT:START -->
```bash
#!/usr/bin/env bash
# CloudIDE: unzip the project, back it up to INTERNAL storage, commit and push to GitHub.
#
#   bash termux-push.sh [path/to/IDE-v<version>-<date>-<time>.zip]   (default: the newest IDE*.zip in Download)
#
# Sign-in: the script uses GitHub CLI and asks you to approve a code in the browser (token only as a fallback).
#
# Defaults can be changed with environment variables:
#   REPO_OWNER / REPO_NAME / GIT_EMAIL   the GitHub account and repository (nothing is baked in: when not set,
#                                        they are read from the working copy's origin remote, then the GitHub CLI
#                                        login and git config, and only asked for if still unknown)
#   WORKDIR                              where the working copy lives (inside Termux)
#   BACKUP_DIR                           where backups go (internal storage, survives uninstalling Termux)
#   REMOTE                               full remote URL; when set, no token is asked for (used for testing)
set -euo pipefail

REPO_OWNER="${REPO_OWNER:-}"
REPO_NAME="${REPO_NAME:-}"
GIT_EMAIL="${GIT_EMAIL:-}"
GIT_NAME="${GIT_NAME:-}"
WORKDIR="${WORKDIR:-$HOME/IDE}"

# Account and repository come from what is already on this phone, never from the zip:
#   1. the origin remote of the existing working copy   2. the GitHub CLI login   3. git config   4. ask
if [ -d "$WORKDIR/.git" ]; then
  RURL="$(git -C "$WORKDIR" remote get-url origin 2>/dev/null || true)"
  if [[ "$RURL" =~ github\.com[:/]([^/]+)/([^/]+)$ ]]; then
    [ -n "$REPO_OWNER" ] || REPO_OWNER="${BASH_REMATCH[1]}"
    [ -n "$REPO_NAME" ] || REPO_NAME="${BASH_REMATCH[2]%.git}"
  fi
fi
if [ -z "$REPO_OWNER" ] && command -v gh >/dev/null 2>&1; then
  REPO_OWNER="$(gh api user -q .login 2>/dev/null || true)"
fi
[ -n "$GIT_EMAIL" ] || GIT_EMAIL="$(git -C "$WORKDIR" config --get user.email 2>/dev/null || git config --global --get user.email 2>/dev/null || true)"
[ -n "$GIT_NAME" ] || GIT_NAME="$(git -C "$WORKDIR" config --get user.name 2>/dev/null || git config --global --get user.name 2>/dev/null || true)"
[ -n "$REPO_OWNER" ] || read -rp "GitHub username: " REPO_OWNER
[ -n "$REPO_NAME" ] || { read -rp "Repository name [$(basename "$WORKDIR")]: " REPO_NAME; REPO_NAME="${REPO_NAME:-$(basename "$WORKDIR")}"; }
[ -n "$GIT_EMAIL" ] || read -rp "Git email (e.g. name@gmail.com): " GIT_EMAIL
GIT_NAME="${GIT_NAME:-$REPO_OWNER}"
BRANCH="main"
DOWNLOADS="${DOWNLOADS:-$HOME/storage/shared/Download}"
ZIP="${1:-$(ls -t "$DOWNLOADS"/IDE*.zip 2>/dev/null | head -n 1)}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/storage/shared/Backups/CloudIDE}"
KEEP=15   # how many backups to keep

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- tools and storage
if command -v apt-get >/dev/null 2>&1; then
  say "Installing git, zip and unzip"
  apt-get install -y git zip unzip >/dev/null
fi
if [ ! -d "$HOME/storage/shared" ] && [ "$BACKUP_DIR" = "$HOME/storage/shared/Backups/CloudIDE" ]; then
  say "Allow storage access in the dialog that appears, then run this script again"
  termux-setup-storage
  exit 0
fi
[ -n "$ZIP" ] && [ -f "$ZIP" ] || die "No IDE*.zip found in $DOWNLOADS (copy the new zip there, or pass its path)"
unzip -tq "$ZIP" >/dev/null 2>&1 || die "$(basename "$ZIP") is damaged or not fully downloaded. Download it again, wait until it has finished, then run this again."

# The backup must live in INTERNAL storage. A folder inside Termux is deleted with Termux, so it is refused.
case "$(readlink -f "$BACKUP_DIR" 2>/dev/null || echo "$BACKUP_DIR")" in
  /storage/*|/sdcard*|/mnt/*) ;;
  *) [ -n "${ALLOW_ANY_BACKUP_DIR:-}" ] || die "BACKUP_DIR must be in internal storage (/storage/emulated/0/...), not inside Termux: $BACKUP_DIR" ;;
esac
mkdir -p "$BACKUP_DIR" "$HOME/bin"

# ---------------------------------------------------------------- backup tool (also used by the commit hook)
cat > "$HOME/bin/idebackup" <<EOF
#!/usr/bin/env bash
# Backs up the WHOLE project to internal storage: a zip (every file, .git included) and a git bundle (full history).
set -e
SRC="\${1:-$WORKDIR}"
DEST="\${BACKUP_DIR:-$BACKUP_DIR}"
STAMP="\$(date +%Y%m%d-%H%M%S)"
mkdir -p "\$DEST"
cd "\$SRC"
zip -qr "\$DEST/IDE-\$STAMP.zip" .   # everything, including the .git folder
if [ -d .git ] && git rev-parse HEAD >/dev/null 2>&1; then git bundle create "\$DEST/IDE-\$STAMP.bundle" --all >/dev/null 2>&1; fi
# keep only the newest $KEEP of each kind
for ext in zip bundle; do
  ls -1t "\$DEST"/IDE-*.\$ext 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do rm -f "\$old"; done
done
echo "Backup saved: \$DEST/IDE-\$STAMP.zip (+ .bundle)"
EOF
chmod +x "$HOME/bin/idebackup"
export BACKUP_DIR

# ---------------------------------------------------------------- back up what is there now, then unpack the new project
if [ -d "$WORKDIR" ] && [ -n "$(ls -A "$WORKDIR" 2>/dev/null)" ]; then
  say "Backing up the current working copy first"
  bash "$HOME/bin/idebackup" "$WORKDIR"
fi

say "Using $(basename "$ZIP")"
TMP="$(mktemp -d)"
unzip -q "$ZIP" -d "$TMP"
# the zip holds one project folder (IDE-main); a zip with the files directly inside works too
if [ "$(find "$TMP" -mindepth 1 -maxdepth 1 | wc -l)" = 1 ] && [ -d "$(find "$TMP" -mindepth 1 -maxdepth 1)" ]; then
  SRC="$(find "$TMP" -mindepth 1 -maxdepth 1)"
else
  SRC="$TMP"
fi
[ -n "$(ls -A "$SRC" 2>/dev/null)" ] || die "The zip is empty"
rm -rf "$SRC/.git"   # never let a .git folder from a zip replace your own history or remote
mkdir -p "$WORKDIR"
# replace the files but keep the .git folder (history) if there is one
find "$WORKDIR" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$SRC"/. "$WORKDIR"/
rm -rf "$TMP"

# ---------------------------------------------------------------- commit
cd "$WORKDIR"
# refresh "Last edited" in README.md to the time this update was applied
[ -f tools/stamp.sh ] && bash tools/stamp.sh >/dev/null || true
[ -d .git ] || git init -q -b "$BRANCH"
git config user.name "$GIT_NAME"
git config user.email "$GIT_EMAIL"
git checkout -q -B "$BRANCH" 2>/dev/null || true
git add -A
if git diff --cached --quiet; then
  say "Nothing changed since the last commit"
else
  VER="$(sed -n "s/.*versionName *= *['\"]\([^'\"]*\)['\"].*/\1/p" app/build.gradle 2>/dev/null | head -n 1)"
  git commit -q -m "CloudIDE ${VER:-update}: $(basename "$ZIP")"
fi

# every future commit is backed up to internal storage automatically
cat > .git/hooks/post-commit <<'EOF'
#!/usr/bin/env bash
"$HOME/bin/idebackup" >/dev/null 2>&1 || true
EOF
chmod +x .git/hooks/post-commit

say "Backing up the new version to internal storage"
bash "$HOME/bin/idebackup" "$WORKDIR"

# ---------------------------------------------------------------- push
if [ -n "${REMOTE:-}" ]; then
  say "Pushing to $REMOTE"
  git push "$REMOTE" "$BRANCH" --force
else
  PLAIN="https://github.com/$REPO_OWNER/$REPO_NAME.git"
  git remote remove origin 2>/dev/null || true
  git remote add origin "$PLAIN"
  # Preferred: sign in through the browser with GitHub CLI (no token to create or paste).
  if ! command -v gh >/dev/null 2>&1; then
    say "Installing GitHub CLI for browser login"
    apt-get install -y gh >/dev/null 2>&1 || pkg install -y gh >/dev/null 2>&1 || true
  fi
  if command -v gh >/dev/null 2>&1; then
    if ! gh auth status -h github.com >/dev/null 2>&1; then
      say "Sign in: gh shows a code. Open github.com/login/device in your browser, enter the code, and approve"
      gh auth login -h github.com -p https -w || die "Browser sign-in did not finish"
    fi
    gh auth setup-git -h github.com >/dev/null 2>&1 || true
    say "Pushing to $PLAIN"
    GIT_TERMINAL_PROMPT=0 git push "$PLAIN" "$BRANCH" --force
  else
    # Fallback when the GitHub CLI cannot be installed: a token typed once, not saved.
    printf '\nGitHub CLI is not available. Paste your GitHub token (nothing is shown while you type), then press Enter: '
    read -rs TOKEN
    echo
    [ -n "$TOKEN" ] || die "No token entered"
    say "Pushing to $PLAIN"
    # The token never goes into the URL (git then reports a bogus "redirecting" warning) or the command line:
    # a temporary askpass helper hands it to git through the environment, for this one command only.
    ASK="$(mktemp)"
    printf '#!/bin/sh\ncase "$1" in Username*) printf %%s "$PUSH_USER" ;; *) printf %%s "$PUSH_TOKEN" ;; esac\n' > "$ASK"
    chmod 700 "$ASK"
    PUSH_USER="$REPO_OWNER" PUSH_TOKEN="$TOKEN" GIT_ASKPASS="$ASK" GIT_TERMINAL_PROMPT=0 git push "$PLAIN" "$BRANCH" --force
    rm -f "$ASK"
    unset TOKEN
  fi
  git config "branch.$BRANCH.remote" origin
  git config "branch.$BRANCH.merge" "refs/heads/$BRANCH"
fi

say "Done."
echo "Working copy (inside Termux, NOT a backup): $WORKDIR"
echo "FULL PROJECT BACKUPS (internal storage):    /storage/emulated/0/Backups/CloudIDE  ($BACKUP_DIR)"
ls -1t "$BACKUP_DIR"/IDE-*.zip 2>/dev/null | head -n 3
echo "Later changes:  cd $WORKDIR && git add -A && git commit -m 'message' && git push origin $BRANCH"
echo "(git asks for a username and password: the username is $REPO_OWNER, the password is your token. Every commit is backed up automatically.)"
```
<!-- SCRIPT:END -->

## Backups (mandatory)

**The full project must always be backed up in the phone's INTERNAL storage, never only in Termux.**
Everything inside Termux (`~/IDE`) is deleted if Termux is uninstalled or its data is cleared, and the GitHub account can be banned.
Internal storage survives both.

- Backup folder: `/storage/emulated/0/Backups/CloudIDE/` (in Termux: `~/storage/shared/Backups/CloudIDE`)
- Each backup is two files: `IDE-<date>-<time>.zip` (every file of the project, `.git` included) and `IDE-<date>-<time>.bundle` (full git history).
- A backup is made on every apply (`termux-push.sh`) and automatically after every `git commit`. The newest 15 are kept.
- Back up by hand at any time: `bash ~/bin/idebackup`
- Check that it worked: `ls -lh ~/storage/shared/Backups/CloudIDE`
- Restore from a zip: `unzip IDE-<date>-<time>.zip -d ~/IDE`. Restore with history: `git clone IDE-<date>-<time>.bundle ~/IDE`
- The script refuses to use a backup folder that is inside Termux.

## Edit history (newest first)

<!-- HISTORY -->
- 2026-10-30 21:00 - v4.5.10 - No .git inside the zip; termux-push.sh no longer hardcodes account or repo (reads origin remote / gh login)
- 2026-10-30 20:39 - v4.5.9 - termux-push.sh: browser login with GitHub CLI instead of token
- 2026-10-30 20:26 - v4.5.8 - Libraries settings page: repository options (JitPack and more), download once, offline mode
- 2026-10-30 05:14 - v4.5.7 - termux-push.sh: fixed cause of apt and git warnings (apt-get, token via GIT_ASKPASS instead of in the URL)
- 2026-10-30 05:12 - v4.5.6 - termux-push.sh: hide apt CLI warning and git redirect warning
- 2026-10-30 05:08 - v4.5.5 - Folder picker for open/import folder, projects folder and toolchain import; removed hardcoded GitHub account (username, token and email fields); Termux script asks for username/email
- 2026-10-28 04:24 - v4.5.4 - README now contains the backup and push script and the Termux command (kept in sync by stamp.sh); script refuses a damaged zip
- 2026-10-28 04:20 - v4.5.3 - README and script: the full project must be backed up to internal storage, not only Termux; backup zip now includes .git; script refuses a backup folder inside Termux
- 2026-10-28 04:19 - v4.5.2 - Fixed the editor hiding the lower part of the code (90dp bottom padding clipped the text); added this README and tools/stamp.sh
- 2026-10-27 - v4.5.1 - Bug-fix, performance and cleanup pass over the editor, project list and build engine (see CHANGELOG.md)
- v4.5.0 - Version of the original upload
