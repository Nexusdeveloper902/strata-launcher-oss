#!/bin/sh
# Before squash-merging a branch: is the mirror's CI result for it green AND about the merge that will happen?
# A mirror PR's CI tests refs/pull/N/merge (the branch merged into the mirror's main) at the time the run starts.
# GitHub does not re-run it when main moves, so a green run can describe a merge base that is no longer main.
# Passes when the newest pull_request run of CI for <branch> on the mirror
#   - succeeded,
#   - ran on the branch's current mirror head, and
#   - started after the mirror's current main was pushed (the push run of CI for that main commit).
# Otherwise it says why and exits 1: sync again (tools/mirror/sync-public.sh <branch>, which also syncs main) and
# wait for the new run. Usage: tools/mirror/check-pr-fresh.sh <branch>     (needs gh, REST only)
set -eu

repo="${STRATA_PUBLIC_REPO:-Nexusdeveloper902/strata-launcher-oss}"
branch="${1:?usage: check-pr-fresh.sh <branch>}"

main_sha="$(gh api "repos/$repo/commits/main" -q .sha)"
head_sha="$(gh api "repos/$repo/commits/$branch" -q .sha)"
main_pushed="$(gh api "repos/$repo/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha=$main_sha&per_page=1" \
  -q '.workflow_runs[0].created_at // ""')"
run="$(gh api "repos/$repo/actions/workflows/ci.yml/runs?branch=$branch&event=pull_request&per_page=1" \
  -q '.workflow_runs[0] | "\(.id) \(.status) \(.conclusion) \(.head_sha) \(.created_at)"')"

fail() { echo "check-pr-fresh: $branch: $*" >&2; exit 1; }
[ -n "$run" ] || fail "no pull_request CI run on the mirror (open a CI-only PR there)"
set -- $run
id="$1"; status="$2"; conclusion="$3"; run_head="$4"; run_created="$5"
[ "$status" = completed ] || fail "run $id is still $status"
[ "$conclusion" = success ] || fail "run $id concluded $conclusion"
[ "$run_head" = "$head_sha" ] || fail "run $id tested $run_head, the branch is now at $head_sha"
[ -n "$main_pushed" ] || fail "no CI push run for mirror main $main_sha: sync main first"
# ISO-8601 UTC timestamps compare correctly as strings.
if [ "$run_created" \< "$main_pushed" ]; then
  fail "run $id ($run_created) started before main $main_sha reached the mirror ($main_pushed): its merge base is stale"
fi
echo "check-pr-fresh: $branch: run $id is green on $head_sha, merged with current main $main_sha"
