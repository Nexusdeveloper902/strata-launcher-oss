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
  pushes branches, and always brings the mirror's `main` up to date too (a mirror PR's CI tests the
  branch merged into the *mirror's* `main`). The filter is deterministic, so syncs are
  fast-forwards; never force-push the mirror. Before pushing it verifies the filtered history and fails closed (any error
  aborts). Everything is read from the branches being synced, never from the working tree:
  - no stripped file and no path under a stripped directory may remain in any commit;
  - no canary may appear in any file of any filtered commit (binary files included), in any
    path, commit message, author or committer name or e-mail, or ref name. The canaries are
    every package of the overlay's `index.txt` (whole-word match) minus the short reviewed
    allowlist `ALLOW` in the script (index packages that legitimately appear in public files),
    plus the extra strings of the overlay's `canaries.txt` and of the file named by
    `STRATA_MIRROR_CANARIES`, merged over all synced branches;
  - a branch that has (or had) the overlay without a usable canary list, or an empty merged
    list, makes the sync refuse. The script always prints the number of canaries it checked.
- `tools/mirror/test-sync.sh` proves these refusals on a scratch repository with invented
  package names (dry runs only); CI runs it in the static-gates job.
- `tools/mirror/sync-public.sh --dry-run DIR <branch>...` filters and verifies the same way, then
  clones the filtered branches into the new directory `DIR` instead of pushing, so the public
  tree can be inspected and built locally.
- Flow per change:
  1. Push the branch here and run `tools/mirror/sync-public.sh <branch>`. Open a PR on the mirror
     to run CI.
  2. If the change touches device tests (`app/src/androidTest`, `DeviceState.kt`), the benchmark
     module, test options in `build-logic`, or `nightly.yml`, also run the nightly matrix on the
     branch: `gh workflow run nightly.yml -R Nexusdeveloper902/strata-launcher-oss --ref <branch>`.
     PR CI covers only API 26/34/36. The nightly matrix is not a merge gate, but a new failure there is
     fixed before the merge.
  3. Before merging, run `tools/mirror/check-pr-fresh.sh <branch>`. It passes only when the newest PR
     run is green, ran on the branch's current head, and started after the mirror's current `main` was
     pushed. Otherwise sync again and wait for the new run.
  4. Squash-merge the PR **here**, run `tools/mirror/sync-public.sh main`, close the mirror PR.
     Nothing is merged on the mirror.
  5. Re-check `main`: the sync starts CI on the mirror's `main` (a `push` run). Watch it
     (`gh run watch -R Nexusdeveloper902/strata-launcher-oss <id>`). If it is red, `main` is broken
     until it is fixed or reverted. Follow `docs/ci/RELIABILITY.md` to tell a flake from a real
     failure. CI can also be re-run on `main` by hand (`gh workflow run ci.yml --ref main`).
- Flaky tests, the re-run rule and how failures are classified: `docs/ci/RELIABILITY.md`.
- This repository stays the source of truth.

## Release APK from CI (for the owner's phone)
The debug APK (`strata-debug-apk`) carries debug-only overhead: StrictMode `detectAll` with CloseGuard, no R8, debug
logging. For daily use, install the **release** APK. The `build` job of every CI run builds it, checks it
(`checkReleaseApk`, T-CI-07), verifies its signature (`apksigner verify --print-certs`) and writes the signer's
certificate SHA-256 into the run's summary.

| Run | Signed with | Artifact |
|---|---|---|
| push to `main`, manual run (`workflow_dispatch`), with the four secrets set | the stable release key | `strata-release-apk` |
| pull request, or no secrets yet | the runner's throw-away debug key | `strata-release-apk-unsigned-test` (file `strata-release-TEST-not-for-install-….apk`) |

Each runner creates a new debug key, so two CI runs never share a signature (checked: main runs 43652228837 and
43599000906 have different certificates). Only `strata-release-apk` can update an installed copy. The secrets are read
only on push and manual runs, never on `pull_request`, and are never printed. The keystore is decoded to the runner's
temp directory (outside the workspace) and deleted when the step ends.

One-time setup, on your computer, never inside the repository:
1. Create the key (the same as `docs/release/RELEASE_GATE.md` §3.11; reuse that key if you already have one):
   `keytool -genkeypair -v -keystore ~/keys/strata-release.jks -alias strata -keyalg RSA -keysize 4096 -validity 10000`
2. Base64 it into one line: `base64 -w 0 ~/keys/strata-release.jks > ~/keys/strata-release.b64` (Linux) or
   `base64 -i ~/keys/strata-release.jks -o ~/keys/strata-release.b64` (macOS).
3. On `Nexusdeveloper902/strata-launcher-oss`: Settings → Secrets and variables → Actions → New repository secret,
   four times: `STRATA_RELEASE_KEYSTORE_BASE64` (the contents of the `.b64` file), `STRATA_RELEASE_STORE_PASSWORD`,
   `STRATA_RELEASE_KEY_ALIAS` (`strata`), `STRATA_RELEASE_KEY_PASSWORD`. Then delete the `.b64` file. Back up the
   `.jks` and its passwords: a lost key means no more in-place updates.
4. Run CI on `main` (`gh workflow run ci.yml -R Nexusdeveloper902/strata-launcher-oss --ref main`), download
   `strata-release-apk`, and note the certificate SHA-256 from the run summary.

Switching the phone from the debug APK to the release APK takes **one uninstall**, because the signatures differ.
Back up first in Strata (Settings → Backup and restore → Export), uninstall, install the release APK, then restore the backup
(Settings → Backup and restore → Restore from file). Every later `strata-release-apk` from `main` installs over it in place, as long as the
summary shows the same certificate SHA-256.

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
