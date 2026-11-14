#!/bin/sh
# Mirrors branches of this private repository to the public repository
# Nexusdeveloper902/strata-launcher-oss, with the research files that describe
# the original app removed from every commit (see research/UNRESOLVED.md U-001,
# U-003, U-004), and the private glyph overlay (platform/icons/glyphs-private/:
# per-app glyphs and index lines for the owner's apps, CONTRIBUTING.md) removed
# from every commit too. The filter is deterministic: the same private commit always maps
# to the same public commit, so every sync is a fast-forward and never needs a
# force-push.
#
# Usage: tools/mirror/sync-public.sh [--dry-run DIR] [branch ...]    (default: main)
#
#   --dry-run DIR  filter and verify, then clone the filtered branches into DIR
#                  (a new directory) instead of pushing. Nothing leaves this machine.
#
# Before pushing, the filtered history is verified: no stripped path may remain in
# any commit, and no canary string may appear in any file of any commit or in any
# commit message. Canaries are fixed strings, one per line, read from
# platform/icons/glyphs-private/canaries.txt (private, so never mirrored) in the
# working tree, or from the file named by STRATA_MIRROR_CANARIES. If the private
# overlay exists but no canary file does, the sync refuses to run.
#
# The private repository stays the source of truth. Pull requests are opened on
# the public repository only to run CI there (Actions minutes are free for public
# repositories); merges happen on the private repository, then main is synced.
set -eu

PUBLIC_URL="${STRATA_PUBLIC_URL:-https://github.com/Nexusdeveloper902/strata-launcher-oss}"
STRIP="research/APK_ANALYSIS.md research/PUBLIC_BEHAVIOR.md research/EVIDENCE.md"
# Directories removed from every commit (every path below them).
STRIP_DIRS="platform/icons/glyphs-private"

dry_run=""
if [ "${1:-}" = "--dry-run" ]; then
  [ "$#" -ge 2 ] || { echo "sync-public: --dry-run needs a directory" >&2; exit 2; }
  dry_run="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
  [ ! -e "$dry_run" ] || { echo "sync-public: $dry_run already exists" >&2; exit 2; }
  shift 2
fi

command -v git-filter-repo >/dev/null 2>&1 || {
  echo "sync-public: git-filter-repo is required (pip install git-filter-repo)" >&2
  exit 1
}

src="$(git rev-parse --show-toplevel)"
[ "$#" -gt 0 ] || set -- main

canaries="${STRATA_MIRROR_CANARIES:-$src/platform/icons/glyphs-private/canaries.txt}"
if [ ! -s "$canaries" ]; then
  for d in $STRIP_DIRS; do
    if [ -e "$src/$d" ]; then
      echo "sync-public: $d exists but there is no canary list ($canaries); refusing to run" >&2
      exit 1
    fi
  done
  canaries=""
else
  # Copy before the work directory is used: the check must not depend on the source tree afterwards.
  canaries_copy="$(mktemp)"
  grep -v '^[[:space:]]*\(#\|$\)' "$canaries" >"$canaries_copy" || true
  canaries="$canaries_copy"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work" ${canaries:+"$canaries"}' EXIT

git init -q --bare "$work/repo"
cd "$work/repo"
for b in "$@"; do
  # Prefer the remote-tracking ref (what was pushed), fall back to the local branch.
  if git -C "$src" rev-parse -q --verify "refs/remotes/origin/$b" >/dev/null; then
    git fetch -q "$src" "+refs/remotes/origin/$b:refs/heads/$b"
  else
    git fetch -q "$src" "+refs/heads/$b:refs/heads/$b"
  fi
done

args=""
for p in $STRIP; do args="$args --path $p"; done
for d in $STRIP_DIRS; do args="$args --path $d/"; done
# shellcheck disable=SC2086
git filter-repo --force --invert-paths $args --refs $(printf 'refs/heads/%s ' "$@") >/dev/null

paths="$(git log --format= --name-only "$@" --)"
for p in $STRIP; do
  if printf '%s\n' "$paths" | grep -qxF "$p"; then
    echo "sync-public: $p still present after filtering; refusing to push" >&2
    exit 1
  fi
done
for d in $STRIP_DIRS; do
  if printf '%s\n' "$paths" | grep -qF "$d/"; then
    echo "sync-public: a path under $d/ is still present after filtering; refusing to push" >&2
    exit 1
  fi
done
if [ -n "$canaries" ] && [ -s "$canaries" ]; then
  # git grep: 0 = match, 1 = no match, anything else = error (treated as a failure, never as clean).
  # shellcheck disable=SC2046
  status=0
  git grep -q -I -F -f "$canaries" $(git rev-list "$@") -- || status=$?
  if [ "$status" -ne 1 ]; then
    echo "sync-public: a canary string is still present in a filtered commit (git grep status $status); refusing to push" >&2
    exit 1
  fi
  if git log --format=%B "$@" | grep -q -F -f "$canaries"; then
    echo "sync-public: a canary string is present in a commit message; refusing to push" >&2
    exit 1
  fi
  echo "sync-public: verified: no stripped path and none of $(wc -l <"$canaries") canaries in the filtered history" >&2
fi

if [ -n "$dry_run" ]; then
  git clone -q "$work/repo" "$dry_run"
  for b in "$@"; do git -C "$dry_run" branch -q --track "$b" "origin/$b" 2>/dev/null || true; done
  git -C "$dry_run" checkout -q "$1"
  echo "sync-public: dry run: filtered branches cloned into $dry_run; nothing was pushed" >&2
  exit 0
fi

git remote add public "$PUBLIC_URL"
for b in "$@"; do
  # --no-verify: the pre-push hook guards the private main; mirroring main to the
  # public repository is the one intended push to a main branch.
  git push --no-verify public "refs/heads/$b:refs/heads/$b"
done
