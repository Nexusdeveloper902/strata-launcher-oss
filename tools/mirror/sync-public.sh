#!/bin/sh
# Mirrors branches of this private repository to the public repository
# Nexusdeveloper902/strata-launcher-oss, with the research files that describe
# the original app removed from every commit (see research/UNRESOLVED.md U-001,
# U-003, U-004), and the private overlays removed from every commit too: the glyph
# overlay (platform/icons/glyphs-private/: per-app glyphs and index lines for the
# owner's apps) and the theme overlay (themes-private/: private built-in presets with
# their wallpapers and credits), see CONTRIBUTING.md.
#
# Usage: tools/mirror/sync-public.sh [--dry-run DIR] [branch ...]    (default: main)
#
#   --dry-run DIR  filter and verify, then clone the filtered branches into DIR
#                  (a new directory) instead of pushing. Nothing leaves this machine.
#
# The filter is deterministic: the same private commit always maps to the same
# public commit, so every sync is a fast-forward and never needs a force-push.
#
# Verification (fails closed: any error aborts before the push). Everything is read
# from the branches being synced, never from the working tree:
# - no stripped file and no path under a stripped directory remains in any commit;
# - canaries never appear in any file of any filtered commit (binary files included),
#   in any path, in any commit message, author or committer name or e-mail, or in
#   any ref name. Canaries are, merged over every synced branch and every overlay
#   (from the latest commit of the branch's history that has that overlay):
#     * every package of the overlay's index.txt (matched as whole words), minus the
#       reviewed allowlist ALLOW below;
#     * the extra fixed strings of the overlay's canaries.txt (matched anywhere);
#     * the fixed strings of the file named by STRATA_MIRROR_CANARIES, if set.
# - no image file (.webp, .png, .jpg) that was ever in an overlay may remain in any
#   filtered commit under any path or name (its blob is matched, not its name);
# - A synced branch that has (or had) an overlay must carry a usable canary list for
#   that overlay (index.txt or canaries.txt), and the merged list must not be empty;
#   otherwise the sync refuses to run.
#
# The private repository stays the source of truth. Pull requests are opened on
# the public repository only to run CI there (Actions minutes are free for public
# repositories); merges happen on the private repository, then main is synced.
set -eu

PUBLIC_URL="${STRATA_PUBLIC_URL:-https://github.com/Nexusdeveloper902/strata-launcher-oss}"
STRIP="research/APK_ANALYSIS.md research/PUBLIC_BEHAVIOR.md research/EVIDENCE.md"
# Directories removed from every commit (every path below them): the private overlays.
OVERLAYS="platform/icons/glyphs-private themes-private"
STRIP_DIRS="$OVERLAYS"
# Reviewed allowlist: overlay index packages that legitimately appear in public files
# (a platform app the device tests and gates refer to, and a build-tool group id).
ALLOW="com.android.settings com.pinterest"

die() {
  echo "sync-public: $*; refusing to push" >&2
  exit 1
}

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
# A push always brings the mirror's main up to date too: a mirror PR's CI tests refs/pull/N/merge, i.e. the branch
# merged into the MIRROR's main. If that main lags the private one, CI tests a merge that will never exist
# (docs/ci/RELIABILITY.md, "Green PR, red main"). Pushing main is a fast-forward like every other sync. A dry run
# inspects exactly the branches it was given.
# main goes FIRST: the branch push starts the PR run, which must already see the new main (check-pr-fresh.sh compares
# the run's start with main's push; a run started before main arrived would stay stale however often it is re-synced).
if [ -z "$dry_run" ]; then
  rest=""
  for b in "$@"; do [ "$b" = main ] || rest="$rest $b"; done
  # shellcheck disable=SC2086
  set -- main $rest
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pkgs="$work/canary-packages"
extra="$work/canary-strings"
: >"$pkgs"
: >"$extra"

# Fixed strings of a canary file: no comments, no blank lines, trimmed.
clean() {
  sed -e 's/#.*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | grep -v '^$' || true
}

# The mirror's main must match the private main as it is now, not as last fetched.
if [ -z "$dry_run" ] && git -C "$src" remote get-url origin >/dev/null 2>&1; then
  git -C "$src" fetch -q origin main || die "could not fetch origin main"
fi

git init -q --bare "$work/repo"
cd "$work/repo"
for b in "$@"; do
  # main is only ever mirrored from origin/main (what was merged), never from a local main.
  if [ "$b" = main ] && [ -z "$dry_run" ]; then
    git -C "$src" rev-parse -q --verify refs/remotes/origin/main >/dev/null ||
      die "no origin/main in $src (fetch it first; a local main is never mirrored)"
    git fetch -q "$src" "+refs/remotes/origin/main:refs/heads/main"
    continue
  fi
  # Prefer the remote-tracking ref (what was pushed), fall back to the local branch.
  if git -C "$src" rev-parse -q --verify "refs/remotes/origin/$b" >/dev/null; then
    git fetch -q "$src" "+refs/remotes/origin/$b:refs/heads/$b"
  else
    git fetch -q "$src" "+refs/heads/$b:refs/heads/$b"
  fi
done

# Canaries, from the branches themselves (before filtering).
overlay_seen=""
for b in "$@"; do
  for o in $OVERLAYS; do
    last="$(git log -1 --format=%H "refs/heads/$b" -- "$o")"
    [ -n "$last" ] || continue
    overlay_seen=1
    # The latest commit that has the overlay: the last one touching it, or its parent if that one deleted it.
    at="$last"
    git cat-file -e "$at:$o" 2>/dev/null || at="$last^"
    n_before=$(cat "$pkgs" "$extra" | wc -l)
    if git cat-file -e "$at:$o/index.txt" 2>/dev/null; then
      git show "$at:$o/index.txt" | clean | awk '{ print $1 }' >>"$pkgs"
    fi
    if git cat-file -e "$at:$o/canaries.txt" 2>/dev/null; then
      git show "$at:$o/canaries.txt" | clean >>"$extra"
    fi
    n_after=$(cat "$pkgs" "$extra" | wc -l)
    [ "$n_after" -gt "$n_before" ] || die "branch $b has $o but no usable canary list (index.txt or canaries.txt)"
  done
done
if [ -n "${STRATA_MIRROR_CANARIES:-}" ]; then
  clean <"$STRATA_MIRROR_CANARIES" >>"$extra"
fi
for a in $ALLOW; do
  grep -vxF "$a" "$pkgs" >"$pkgs.tmp" || true
  mv "$pkgs.tmp" "$pkgs"
done
sort -u -o "$pkgs" "$pkgs"
sort -u -o "$extra" "$extra"
n_pkgs=$(wc -l <"$pkgs")
n_extra=$(wc -l <"$extra")
n=$((n_pkgs + n_extra))
if [ -n "$overlay_seen" ] && [ "$n" -eq 0 ]; then
  die "the synced branches have a private overlay but the canary list is empty"
fi

# Every image blob that was ever in an overlay on the synced branches (post-images of their changes).
media="$work/overlay-media"
# shellcheck disable=SC2086
git log --format= --raw --no-abbrev --no-renames "$@" -- $OVERLAYS |
  awk '{ print $4, $6 }' | grep -iE '[.](webp|png|jpe?g)$' | awk '$1 !~ /^0+$/ { print $1 }' | sort -u >"$media"

args=""
for p in $STRIP; do args="$args --path $p"; done
for d in $STRIP_DIRS; do args="$args --path $d/"; done
# shellcheck disable=SC2086
git filter-repo --force --invert-paths $args --refs $(printf 'refs/heads/%s ' "$@") >/dev/null

# Matches the canaries against a file: 0 = clean, anything else aborts.
scan_file() {
  for list in "$pkgs" "$extra"; do
    [ -s "$list" ] || continue
    w=""
    [ "$list" = "$pkgs" ] && w="-w"
    status=0
    # shellcheck disable=SC2086
    grep -q -a $w -F -f "$list" "$1" || status=$?
    [ "$status" -eq 1 ] || return 1
  done
  return 0
}

paths="$work/paths"
git log --format= --name-only --no-renames "$@" -- >"$paths"
for p in $STRIP; do
  if grep -qxF "$p" "$paths"; then die "$p still present after filtering"; fi
done
for d in $STRIP_DIRS; do
  if grep -q "^$d/" "$paths"; then die "a path under $d/ is still present after filtering"; fi
done
scan_file "$paths" || die "a canary string is present in a file path"

if [ -s "$media" ]; then
  git rev-list --objects "$@" | awk '{ print $1 }' | sort -u >"$work/objects"
  if [ -n "$(comm -12 "$media" "$work/objects")" ]; then
    die "a private overlay image is still present in a filtered commit"
  fi
fi

meta="$work/meta"
git log --format='%B%n%an%n%ae%n%cn%n%ce' "$@" -- >"$meta"
git for-each-ref --format='%(refname)' >>"$meta"
scan_file "$meta" || die "a canary string is present in a commit message, an identity or a ref name"

# File contents of every filtered commit, binary files included, in batches (no ARG_MAX limit).
revs="$work/revs"
git rev-list "$@" >"$revs"
for list in "$pkgs" "$extra"; do
  [ -s "$list" ] || continue
  w=""
  [ "$list" = "$pkgs" ] && w="-w"
  hits="$work/hits"
  # Each batch prints MATCH on a hit and fails on any git grep error (status other than 0 or 1).
  # shellcheck disable=SC2016
  W="$w" xargs -n 64 sh -c 's=0; git grep -q -a $W -F -f "$0" "$@" -- || s=$?
    if [ "$s" -eq 0 ]; then echo MATCH; elif [ "$s" -ne 1 ]; then exit 1; fi' "$list" <"$revs" >"$hits" ||
    die "git grep failed while scanning file contents"
  if [ -s "$hits" ]; then die "a canary string is still present in a filtered commit"; fi
done

echo "sync-public: verified: no stripped path, none of $(wc -l <"$media") overlay images, and none of $n canaries" \
  "($n_pkgs packages, $n_extra strings) in paths, file contents (binary included), messages, identities or ref names" >&2

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
