#!/bin/sh
# Tests for tools/mirror/sync-public.sh (dry runs only; nothing is pushed anywhere).
# Builds a scratch repository with invented package names and proves that the sync
# refuses whenever private content could leak, and passes on a clean history.
#
# Usage: tools/mirror/test-sync.sh        (needs git and git-filter-repo)
set -eu

script="$(cd "$(dirname "$0")" && pwd)/sync-public.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export GIT_AUTHOR_NAME=tester GIT_AUTHOR_EMAIL=tester@example.invalid
export GIT_COMMITTER_NAME=tester GIT_COMMITTER_EMAIL=tester@example.invalid
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
unset STRATA_MIRROR_CANARIES 2>/dev/null || true

overlay=platform/icons/glyphs-private
themes=themes-private
# An invented preset image: its content hash is a canary of the theme overlay.
img_sha=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
fails=0
n=0

repo="$tmp/repo"
git init -q -b main "$repo"
cd "$repo"
mkdir -p docs app
echo "Settings intent goes to com.android.settings." >docs/readme.txt # allowlisted, so not a leak
echo "public" >app/code.txt
git add -A && git commit -q -m "public start"

# A branch with the private overlay.
git checkout -q -b overlay
mkdir -p "$overlay/res"
printf '# private index\ncom.example.hiddenalpha alpha\ncom.example.hiddenbeta beta\ncom.android.settings settings\n' \
  >"$overlay/index.txt"
printf '# extra strings\nsecretuser_\n' >"$overlay/canaries.txt"
echo "<vector/>" >"$overlay/res/glyph_alpha.xml"
git add -A && git commit -q -m "add overlay"
# The theme overlay: a private preset, its wallpaper and credits; canaries only (no index).
mkdir -p "$themes/presets" "$themes/images"
printf '{"name":"Secretpreset"}\n' >"$themes/presets/x.strata-theme.json"
printf '%s Picture\n' "$img_sha" >"$themes/presets/x.wallpapers.txt"
printf 'RIFF\000\000\000\000WEBPsecretpixels' >"$themes/images/$img_sha.webp"
printf '# theme canaries\nSecretpreset\n%s\n' "$img_sha" >"$themes/canaries.txt"
git add -A && git commit -q -m "add theme overlay"
git checkout -q main

# Branches derived from the overlay branch, one leak each.
leak() { # name message command...
  name="$1" msg="$2"
  shift 2
  git checkout -q -b "$name" overlay
  "$@"
  git add -A && git commit -q -m "$msg"
  git checkout -q main
}
leak leak-text "public note" sh -c 'echo "see com.example.hiddenalpha" >docs/note.txt'
leak leak-binary "add blob" sh -c 'printf "\000\001bin secretuser_x \000" >app/blob.bin'
leak leak-path "add doc" sh -c 'echo x >docs/com.example.hiddenbeta.txt'
leak leak-message "mention com.example.hiddenalpha in the message" sh -c 'echo y >app/other.txt'
leak leak-theme-text "public doc" sh -c 'echo "the Secretpreset look" >docs/theme.txt'
leak leak-theme-image "copy a picture" sh -c 'mkdir -p app/assets && cp "$0"/images/*.webp app/assets/' "$themes"
leak leak-theme-renamed "add a picture" sh -c 'mkdir -p app/assets && cp "$0"/images/*.webp app/assets/pic.webp' "$themes"
git checkout -q -b comments-only main
mkdir -p "$overlay"
printf '# only comments\n\n   # still nothing\n' >"$overlay/canaries.txt"
git add -A && git commit -q -m "overlay without a usable canary list"
git checkout -q main
git checkout -q -b theme-comments-only main
mkdir -p "$themes/presets"
echo '{}' >"$themes/presets/y.strata-theme.json"
printf '# nothing usable\n' >"$themes/canaries.txt"
git add -A && git commit -q -m "theme overlay without a usable canary list"
git checkout -q main
git checkout -q -b theme-removed overlay
git rm -q -r "$themes"
git commit -q -m "drop the theme overlay"
echo "Secretpreset" >docs/late.txt
git add -A && git commit -q -m "late leak"
git checkout -q main

expect() { # pass|refuse description branch... (run from the current directory)
  want="$1" what="$2"
  shift 2
  n=$((n + 1))
  out="$tmp/dry$n"
  if sh "$script" --dry-run "$out" "$@" >"$tmp/log$n" 2>&1; then got=pass; else got=refuse; fi
  # A refusal must be the script's own verdict, not some other failure.
  if [ "$got" = refuse ] && ! grep -q "refusing" "$tmp/log$n"; then got="error"; fi
  if [ "$got" = "$want" ]; then
    echo "ok   $n $what ($got: $(grep -h "sync-public:" "$tmp/log$n" | head -1 | cut -c14-90))"
  else
    echo "FAIL $n $what: expected $want, got $got"
    sed 's/^/     /' "$tmp/log$n"
    fails=$((fails + 1))
  fi
}

expect pass "clean history with both overlays stripped" main overlay
for o in "$overlay" "$themes"; do
  if [ -e "$tmp/dry1/$o" ]; then
    echo "FAIL $o present in the filtered tree"
    fails=$((fails + 1))
  fi
  if [ -n "$(git -C "$tmp/dry1" log --all --format= --name-only -- "$o")" ]; then
    echo "FAIL $o present in the filtered history"
    fails=$((fails + 1))
  fi
done
expect refuse "index package in a public file" leak-text
expect refuse "canary inside a binary file" leak-binary
expect refuse "canary in a file name" leak-path
expect refuse "canary in a commit message" leak-message
expect refuse "canary file with only comments" comments-only
expect refuse "theme canary in a public file" leak-theme-text
expect refuse "preset image copied outside the overlay" leak-theme-image
expect refuse "preset image copied under another name" leak-theme-renamed
expect refuse "theme overlay without a usable canary list" theme-comments-only
expect refuse "theme canary after the overlay was removed" theme-removed

# Run from a worktree that does not have the overlay: canaries still come from the synced branch.
git worktree add -q --detach "$tmp/wt" main
cd "$tmp/wt"
expect refuse "run from a worktree without the overlay" leak-text
cd "$repo"

if [ "$fails" -ne 0 ]; then
  echo "test-sync: $fails of $n cases failed" >&2
  exit 1
fi
echo "test-sync: all $n cases passed"
