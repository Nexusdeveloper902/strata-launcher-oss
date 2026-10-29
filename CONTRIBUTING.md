# Contributing

Solo project, but run like a team one: every change reaches `main` through a pull request.

## Flow
1. Branch from an up-to-date `main`.
2. Commit in small steps; push the branch.
3. Open a PR into `main` (the template asks for milestone/requirement IDs, T-IDs passing, and
   device checks for the Galaxy A56).
4. Merge with **squash** once CI is green. `main` keeps linear history; head branches are
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
./gradlew assembleDebug testDebugUnitTest lintDebug ktlintCheck checkPermissions checkDependencies checkModuleGraph checkEntitlements assembleDebugAndroidTest :build-logic:convention:test
```
If you changed requirement or test IDs, also run `python3 tools/trace/build.py`.
New libraries must pass `checkDependencies` (the allow-list), Dependabot PRs included.

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
- `.github/rulesets/main.json` records the intended rules (PR required, the 8 CI checks
  required, squash-only, linear history, no force-push or deletion). It is not enforced now;
  import it (Settings -> Rules -> Rulesets -> Import) if the repository becomes public or the
  plan is upgraded. Its required checks must match the job names in
  `.github/workflows/ci.yml`; change both together.

## Repository settings (one-time, by the owner)
- Settings -> General -> Pull Requests: allow only squash merging, and enable
  "Automatically delete head branches".
