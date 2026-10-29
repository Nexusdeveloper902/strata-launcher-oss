# Strata Launcher — start here

Personal Android launcher (vertical app list, fully free theme system). The
specification phase is **finished**; the next step is implementation, starting at
**M0** in `spec/IMPLEMENTATION_ROADMAP.md`.

## Rules
- Follow `AGENTS.md` (clean room, evidence labels, ID scheme). Implementers work from
  `spec/` only. Do **not** read `research/APK_ANALYSIS.md` or `research/PUBLIC_BEHAVIOR.md`
  while writing code. They describe the original app; the specs already say what to build.
- Decisions live in `research/DECISIONS.md`. Key ones: D-004 (everything free), D-005
  (Kotlin + Compose, platform APIs behind interfaces), D-007 (offline, no analytics),
  D-011 (personal sideloaded project; primary test phone **Samsung Galaxy A56**; the
  double-tap lock *is* included), D-012 (name/IDs: `app.strata.launcher`).
- Commit straight to `main` (owner preference; no PRs unless asked). Commit at each
  milestone step, with test IDs (`T-…`) named in the message when they pass.
- Choose subagent models per task: `opus` for design, architecture and review,
  `sonnet` for mechanical or bulk work, `haiku` for trivial edits.

## Reading order for M0
1. `spec/IMPLEMENTATION_ROADMAP.md` §0–§2 (M0 scope and definition of done)
2. `spec/ARCHITECTURE.md` (modules, dependency rules, settings catalogue §4.2)
3. `spec/ANDROID_PLATFORM.md` §22 manifest skeleton, §23 permission table
4. `spec/TEST_PLAN.md` tests named in M0's DoD (T-CI-*, T-HOME-01/02, T-ONBOARD-*)
5. `spec/PRODUCT_SPEC.md` for the referenced FR/NFR IDs

## Environment notes (cloud sessions)
- JDK and Gradle 8.14 are present; **no Android SDK** is installed and `ANDROID_HOME` is
  unset. Install the command-line tools + `platforms;android-36` + `build-tools` first
  (needs network access to `dl.google.com`; if blocked, change the environment's network
  policy). No emulator is available here: device tests run on the owner's phone.
- Theme samples: `pip install jsonschema && python3 tools/check_themes.py spec/theme.schema.json spec/sample-themes/*.json`
- Traceability matrix: `python3 tools/trace/build.py` regenerates `spec/REQUIREMENTS_TRACEABILITY.md`.
  Re-run it after any edit to requirement or test IDs.

## Open items that don't block M0
`research/UNRESOLVED.md`: device validation (U-002, U-015…U-020); legal items
(U-001/003/004) matter only if the app is ever shared or published.
