# Contributing

Solo project, but run like a team one: every change reaches `main` through a pull request.

## Flow
1. Branch from an up-to-date `main`.
2. Commit in small steps; push the branch.
3. Open a PR into `main` (the template asks for milestone/requirement IDs, T-IDs passing, and
   device checks for the Galaxy A56).
4. Mirror the branch to the public repository and open a CI-only PR there (see
   "Public mirror and CI" below). Merge the private PR with **squash** once that CI is green. `main` keeps linear history; head branches are
   deleted automatically. No human approval is required (nobody can approve their own PR), but
   review threads must be resolved.

### Branch names
`<type>/<short-kebab-topic>`, with a milestone where it applies: `m1/home-list`,
`fix/lock-service-crash`, `ci/dependabot`, `docs/contributing`.

### Commit messages
Imperative, short subject, milestone prefix where relevant, and name the test IDs that pass:
`M4: theme library (boot, LKG, crash-loop guard) - T-THEME-07, T-THEME-09`,
`Gates: exclude .claude/ from checkEntitlements scan`, `CI: make GMD emulator start failures diagnosable`.
The PR title becomes the squash commit subject, so write it the same way.

## Local check (run before pushing)
```
./gradlew assembleDebug testDebugUnitTest lintDebug ktlintCheck checkPermissions checkDependencies checkModuleGraph checkEntitlements checkLicences assembleDebugAndroidTest :build-logic:convention:test :app:checkReleaseApk :benchmark:assembleBenchmark
```
`:app:checkReleaseApk` builds the release APK (R8) and checks it (T-CI-07: size per ABI, no debuggable flag, no
pseudo-locales, no test code). Device budgets and the v1 release checklist: `docs/release/RELEASE_GATE.md`.
If you changed requirement or test IDs, also run `python3 tools/trace/build.py`.
New libraries must pass `checkDependencies` (the allow-list), Dependabot PRs included.

## Public mirror and CI
GitHub Actions minutes on this private Free-plan repository ran out, so CI runs on the public
mirror [`Nexusdeveloper902/strata-launcher-oss`](https://github.com/Nexusdeveloper902/strata-launcher-oss),
where Actions minutes are free.
- The mirror is this repository with `research/APK_ANALYSIS.md`, `research/PUBLIC_BEHAVIOR.md`
  and `research/EVIDENCE.md` removed from every commit (they describe the original app; see
  `research/UNRESOLVED.md` U-001, U-003, U-004). Never add those files, or content from them,
  to anything that is mirrored.
- `tools/mirror/sync-public.sh <branch>...` (needs `pip install git-filter-repo`) filters and
  pushes branches. The filter is deterministic, so syncs are fast-forwards; never force-push
  the mirror.
- Flow per change: push the branch here, run `tools/mirror/sync-public.sh <branch>`, open a PR
  on the mirror to run CI, squash-merge the PR **here** once the mirror's CI is green, then run
  `tools/mirror/sync-public.sh main` and close the mirror PR. Nothing is merged on the mirror.
- This repository stays the source of truth.

## Clean room
Follow `AGENTS.md`. Implement from `spec/` only. Never open `research/APK_ANALYSIS.md` or
`research/PUBLIC_BEHAVIOR.md` while writing code, and never copy code, strings, artwork or
brand names from the original app.

## Protecting `main`
GitHub only enforces branch rules on private repositories with a paid plan, so on this
private Free-plan repository the rules below are a convention, backed by CI and a local hook:
- Enable the local guard once per clone: `git config core.hooksPath tools/git-hooks`.
  `tools/git-hooks/pre-push` then refuses direct pushes to `main`.
- Merge a pull request only when every CI check is green.
- `.github/rulesets/main.json` records the intended rules (PR required, the 9 CI checks
  required, squash-only, linear history, no force-push or deletion). It is not enforced now;
  import it (Settings -> Rules -> Rulesets -> Import) if the repository becomes public or the
  plan is upgraded. Its required checks must match the job names in
  `.github/workflows/ci.yml`; change both together.

## Repository settings (one-time, by the owner)
- Settings -> General -> Pull Requests: allow only squash merging, and enable
  "Automatically delete head branches".
