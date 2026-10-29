# List-Launcher Clean-Room Specification

Research and an implementation-ready specification for an **independent** Android
launcher: a minimal, vertical-list home screen with a theme system that is
**fully free** and user-controlled.

> **No affiliation.** This project is not affiliated with, endorsed by, or derived
> from the source code of Niagara Launcher or its publisher. "Niagara Launcher"
> is named only as the subject of comparative research. The independent product
> must use its own name, branding, artwork, copy and code.
> The repository name (`Niagara-REA`) was fixed before this work started; see
> `research/DECISIONS.md` D-002 for the recommended rename.

## Status
Specification phase. No launcher code exists here by design. See `research/STATUS.md`.

## Layout
| Path | Purpose |
|---|---|
| `AGENTS.md` | Rules, task-assignment and handoff convention for all agents |
| `research/` | Method, evidence, APK analysis, decisions, unresolved questions |
| `spec/` | The product specification a coding agent implements from |
| `spec/sample-themes/` | Original sample theme files (schema v1) |

## Reading order for an implementing agent
1. `spec/PRODUCT_SPEC.md`
2. `spec/ARCHITECTURE.md` and `spec/ANDROID_PLATFORM.md`
3. `spec/THEME_SYSTEM.md`, `spec/VISUAL_SPEC.md`, `spec/INTERACTION_SPEC.md`
4. `spec/IMPLEMENTATION_ROADMAP.md`, `spec/TEST_PLAN.md`
5. `spec/REQUIREMENTS_TRACEABILITY.md` when checking coverage

The implementing agent should **not** need anything under `research/` except to
check provenance, and must never be given the original APK or extraction output.
