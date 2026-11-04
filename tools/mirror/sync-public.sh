#!/bin/sh
# Mirrors branches of this private repository to the public repository
# Nexusdeveloper902/strata-launcher-oss, with the research files that describe
# the original app removed from every commit (see research/UNRESOLVED.md U-001,
# U-003, U-004). The filter is deterministic: the same private commit always maps
# to the same public commit, so every sync is a fast-forward and never needs a
# force-push.
#
# Usage: tools/mirror/sync-public.sh [branch ...]    (default: main)
#
# The private repository stays the source of truth. Pull requests are opened on
# the public repository only to run CI there (Actions minutes are free for public
# repositories); merges happen on the private repository, then main is synced.
set -eu

PUBLIC_URL="${STRATA_PUBLIC_URL:-https://github.com/Nexusdeveloper902/strata-launcher-oss}"
STRIP="research/APK_ANALYSIS.md research/PUBLIC_BEHAVIOR.md research/EVIDENCE.md"

command -v git-filter-repo >/dev/null 2>&1 || {
  echo "sync-public: git-filter-repo is required (pip install git-filter-repo)" >&2
  exit 1
}

src="$(git rev-parse --show-toplevel)"
[ "$#" -gt 0 ] || set -- main

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

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
# shellcheck disable=SC2086
git filter-repo --force --invert-paths $args --refs $(printf 'refs/heads/%s ' "$@") >/dev/null

for p in $STRIP; do
  if git log --format= --name-only "$@" -- | grep -qx "$p"; then
    echo "sync-public: $p still present after filtering; refusing to push" >&2
    exit 1
  fi
done

git remote add public "$PUBLIC_URL"
for b in "$@"; do
  # --no-verify: the pre-push hook guards the private main; mirroring main to the
  # public repository is the one intended push to a main branch.
  git push --no-verify public "refs/heads/$b:refs/heads/$b"
done
