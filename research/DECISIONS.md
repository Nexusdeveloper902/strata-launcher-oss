# Decisions

| ID | Date | Decision | Rationale |
|---|---|---|---|
| D-001 | 2026-10-29 | Use the pre-existing private repo `Nexusdeveloper902/Niagara-REA` instead of creating a new one. | Session GitHub access is scoped to this repo only and git instructions pin the branch `ccr-a64fdd14-b8h4e5`. Repo confirmed private. |
| D-002 | 2026-10-29 | Recommend renaming the repo to a neutral name (e.g. `list-launcher-spec`) and choosing an independent product name. Working product name in the spec: **"Strata Launcher"** (placeholder). | Current name implies affiliation; renaming is the owner's action. |
| D-003 | 2026-10-29 | APK analysis limited to metadata, manifest, resource keys and class-name inventory; no method-body decompilation. | Purpose (competing product) is not an interoperability purpose under EU Art. 6; terms not reviewed. See RESEARCH_METHOD §3. |
| D-004 | 2026-10-29 | All appearance/theme features are free; no billing library, no account, no backend in v1. | Core product differentiator; no documented need for a server. |
| D-005 | 2026-10-29 | Kotlin + Jetpack Compose UI, with platform `AppWidgetHost`/`LauncherApps` in framework code wrapped behind interfaces. | See spec/ANDROID_PLATFORM.md; Compose does not provide widget hosting. |
| D-006 | 2026-10-29 | Theme files are human-editable JSON with a `schemaVersion`; file extension `.strata-theme.json`. Do not read or write the original app's theme/backup formats. | Avoid dependence on proprietary formats; JSON is diffable and hand-editable. |
| D-007 | 2026-10-29 | No analytics, ads, or crash upload in v1; offline-first. | Privacy; reduces permissions. |
