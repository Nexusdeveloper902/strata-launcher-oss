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
- The private glyph overlay `platform/icons/glyphs-private/` is removed from every commit too.
  It holds the per-app Strata glyphs (FR-THEME-35) for the owner's own apps: `index.txt` (same
  format as `src/main/res/raw/glyph_index.txt`), `res/drawable-nodpi/glyph_<id>.xml`, the
  generator `tools/make_glyphs.py` and `canaries.txt`. The public tree keeps the pipeline, the
  stub index (Strata's own line only) and `glyph_strata.xml`.
  - Build: when `glyphs-private/index.txt` exists, `GenerateGlyphBundle` checks both sets (no
    package in both indexes, no private drawable that redefines a public glyph id, every id a
    single-colour vector), packages the public index followed by the private one as
    `res/raw/glyph_index.txt` (a generated resource layer over the public file), adds
    `glyphs-private/res` as a resource directory, and hashes both into the bundle version.
    Without the directory (the mirror) the packaged index is the public file byte for byte and
    the build is unchanged. T-THEME-55 covers both cases.
  - Never name a private package in anything that is mirrored (tests, fixtures, docs, commit
    messages). Public tests use invented names such as `com.example.*`.
  - Adding a glyph: draw it in `tools/make_glyphs.py`, add its line to `index.txt`, run
    `python3 platform/icons/glyphs-private/tools/make_glyphs.py --check`, then review the sheet
    from `python3 tools/glyphs/contact_sheet.py OUT.png platform/icons/glyphs-private/res/drawable-nodpi`
    (needs `pip install cairosvg pillow`; review tooling only).
- `tools/mirror/sync-public.sh <branch>...` (needs `pip install git-filter-repo`) filters and
  pushes branches. The filter is deterministic, so syncs are fast-forwards; never force-push
  the mirror. Before pushing it verifies the filtered history and aborts if any stripped file,
  any path under a stripped directory, or any canary string (fixed strings from
  `platform/icons/glyphs-private/canaries.txt`, or the file named by `STRATA_MIRROR_CANARIES`)
  is still present in any commit or commit message. It refuses to run when the private
  directory exists but the canary list does not.
- `tools/mirror/sync-public.sh --dry-run DIR <branch>...` filters and verifies the same way, then
  clones the filtered branches into the new directory `DIR` instead of pushing, so the public
  tree can be inspected and built locally.
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
