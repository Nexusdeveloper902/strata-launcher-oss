# Strata Launcher — Theme System Specification

Status: draft for review (T-04). Owner: agent:theme. Date: 2026-10-29.
Companion files: `spec/theme.schema.json` (formal JSON Schema, draft 2020-12),
`spec/sample-themes/*.strata-theme.json` (five original sample themes).

**Clean-room note.** This design is independent. `research/APK_ANALYSIS.md` §5 was used
only as a *feature inventory* (which kinds of appearance controls a list launcher offers).
No file format, key name, default value, font list, palette, clock design or theme content
of the original app was seen or reused. Key names are our own; `icons.legacyIconPlate`
replaced an earlier key whose wording echoed one of the original's setting names (T-07 R-02).
Numeric ranges were chosen from WCAG and system-scale reasoning; the lower bound of
`typography.scale` (0.80) happens to coincide with a value documented for the original
(PUBLIC_BEHAVIOR P3, U-009), which we accept as a functional coincidence (T-07 R-18). The original's theme and backup file types are
neither read nor written (D-006).

**Labels** follow `research/RESEARCH_METHOD.md` §1. Every requirement carries one:
- `Proposed` — our own design; rationale given inline.
- `Inferred (APK-nnn)` — the *capability* mirrors a feature whose existence is evidenced
  only by the APK resource inventory (one source class, so at most Inferred); the way we
  specify it is still our own design.
- `Corroborated` would need a second independent source (e.g. vendor docs matching the APK
  evidence); no row here claims it.
- Platform API facts are Verified in `spec/ANDROID_PLATFORM.md` (DOC-043 adaptive and
  monochrome icons, DOC-045/DOC-046 wallpaper and `WallpaperColors`, DOC-049 dynamic colour).

---

## 1. Goals and non-goals

### 1.1 Goals
1. **Everything free.** Every appearance capability in this document is available to
   every user. No paid tier, no "pro" flag in theme files, no limit on saved themes, no
   locked fonts, icons or clock styles, no nag dialogs (D-004). Contrast: the original
   gates parts of theming behind a paid tier (APK-025); we deliberately do not.
2. **Human-editable, diffable files.** A theme is one UTF-8 JSON document that a person
   can write in a text editor. Only three keys are required; everything else defaults.
3. **Safe by construction.** A theme is pure data. It cannot run code, cannot make the
   app fetch anything from the network, and cannot stop the launcher from starting.
4. **Readable by default.** Built-in and generated palettes meet WCAG 2.2 AA. User-made
   palettes are checked live, with warnings and a one-tap fix.
5. **Forward and backward compatible.** Versioned schema, unknown keys preserved,
   explicit migration chain.
6. **Live, undoable editing.** Every change previews immediately and can be undone.

### 1.2 Non-goals
- **No control over third-party content beyond public APIs.** We do not promise to
  restyle app widgets (they render their own `RemoteViews`), notifications, other apps,
  the system status/navigation bar beyond what `Window`/`WindowInsetsController` allow,
  or third-party icon packs beyond the de-facto public icon-pack conventions those packs
  expose. Icon packs may be incomplete or broken; we fall back, we do not repair them.
- No remote theme gallery, accounts, sync server or online fonts in v1 (D-004, D-007).
- No scripting, expressions, conditional logic or CSS-like selectors inside themes.
- No per-app colour overrides in v1 (possible later as an `x-` extension, then a minor).
- No compatibility with the original app's theme or backup formats (D-006).
- No guarantee of contrast on every pixel of an arbitrary photo wallpaper (see §7.4).

---

## 2. Concepts and resolution order

| Term | Meaning |
|---|---|
| **Theme file** | A `.strata-theme.json` document (§4). |
| **Bundle** | A `.strata-theme.zip` carrying a theme plus user font(s) and/or a wallpaper image (§10.3). |
| **Preset** | A theme stored in the library. *Built-in* presets ship read-only in the APK; *user* presets are created or imported. |
| **Active theme** | The preset currently applied; one pointer in the index (§11). |
| **Resolved theme** | The immutable token set the UI renders, computed from: built-in defaults ← theme file fields ← derived colours (§5.2) ← global overrides (§6) ← contrast enforcement (§7.5). |
| **Strata Default** | Built-in preset whose values equal all schema defaults. Compiled into the app as a Kotlin constant (not parsed from a file) so it is always available, even if storage is unreadable. |

Resolution is a pure function `resolve(themeFile, platformInputs, globalSettings) -> ResolvedTheme`.
`platformInputs` are: API level, system dark-mode flag, `WallpaperColors` (if any), the
dynamic colour scheme (if API 31+), system font scale, animator duration scale, haptic
setting, installed icon packs, and the presence of referenced fonts/images. Same inputs
always produce the same output (NFR-THEME-09).

---

## 3. File identity

| Property | Value |
|---|---|
| Single-file extension | `.strata-theme.json` |
| Single-file MIME type | `application/json` |
| Bundle extension | `.strata-theme.zip` |
| Bundle MIME type | `application/zip` |
| Encoding | UTF-8, RFC 8259 JSON; an optional leading UTF-8 BOM is tolerated and dropped on write |
| Writer format | 2-space indent, LF line endings, trailing newline, known keys in schema order, then unknown keys in their original order |
| Schema `$id` | `https://strata-launcher.invalid/schema/theme/1.0/theme.schema.json` (`.invalid` TLD on purpose: identifier only, never fetched; replace once a product domain exists, U-006) |

Because content providers often label files `application/octet-stream` or `text/plain`,
the importer identifies content by sniffing (first non-whitespace byte `{` → JSON;
bytes `50 4B 03 04` → zip), not by MIME type or name.

### 3.1 Accepted import types (canonical list; every other file refers here)
| Entry point | MIME types offered / matched | Notes |
|---|---|---|
| In-app theme import (SAF `ACTION_OPEN_DOCUMENT`, `EXTRA_MIME_TYPES`) | `application/json`, `application/zip`, `application/octet-stream`, `text/plain` | `text/plain` is accepted **only** here |
| From other apps: `ACTION_VIEW` filter | `application/json`, `application/zip`, `application/octet-stream`; `scheme="content"` only | ANDROID_PLATFORM §22 |
| From other apps: `ACTION_SEND` filter | same three types; the `EXTRA_STREAM` URI must be `content:` (checked in code) | SEND has no data URI, so the filter cannot pin the scheme |
| Font manager (*Add font*, SAF) | `font/ttf`, `font/otf`, `application/octet-stream` | §10.3.3 validation |
| Backup restore (SAF, ARCHITECTURE §12) | `application/json`, `application/octet-stream`, `text/plain` | not offered to other apps |
File names and extensions (`.strata-theme.json`, `.strata-theme.zip`, `.strata-backup.json`)
are suggested on export only; import never relies on them.

---

## 4. Schema (schemaVersion 1, schemaMinor 0)

### 4.1 Top level and metadata

Legend for **On invalid**: **C** = clamp to range; **D** = use the default for rendering
(the original value is preserved on round-trip unless the user edits that field);
**G** = generate a replacement and warn; **T** = truncate; **R** = reject the whole file.

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `schemaVersion` | integer | — (required) | `1` | missing / non-integer / `< 1` → R ("Not a Strata theme"); `> 1` → R ("Made with a newer version", §10.5) |
| `schemaMinor` | integer | `0` | `0..1000` | D (`0`) |
| `id` | string (UUID) | — (required) | 8-4-4-4-12 hex; compared case-insensitively | missing/malformed → G (new random UUID v4) |
| `name` | string | — (required) | 1–64 Unicode grapheme clusters, no control chars (both app-enforced; the schema can only bound code points) | missing/empty → G ("Untitled theme"); too long → T; control characters removed (issue `fixed`) |
| `author` | string | absent | ≤ 64 graphemes, no control chars | T; control characters removed |
| `description` | string | absent | ≤ 500 graphemes, no control chars except line feed | T; other control characters removed |
| `createdAt`, `modifiedAt` | string | absent (set on save) | RFC 3339 date-time | D (drop and re-stamp on save) |
| `basedOn` | string (UUID) | absent | UUID | D (drop) |
| `$schema` | string | absent | ≤ 512 chars | ignored; never fetched; preserved |
| `x-*` | any JSON | — | key `^x-[a-z0-9][a-z0-9._-]{0,62}$` | preserved verbatim, never interpreted by v1 |
| other unknown key | any JSON | — | — | warning; preserved verbatim (§8.3) |

Groups: `colors`, `wallpaper`, `typography`, `list`, `icons`, `clock`, `favorites`,
`search`, `alphabetIndex`, `animation`, `haptics`. Any group may be omitted. A group
that is not a JSON object → D for the whole group (preserved).

General type rules (all groups): a wrong JSON type → D. A number for an `integer`
field is rounded half-to-even, then clamped. A `weight` not a multiple of 100 is
rounded to the nearest 100. An unknown enum string → D, original preserved (a newer
minor may have added that value). Strings exceeding length limits → T.

### 4.2 `colors`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `mode` | enum | `system` | `system` (follow system dark setting), `light`, `dark`, `wallpaper` (choose from the wallpaper, §5.3) | D |
| `source.kind` | enum | `fixed` | `fixed`, `seed`, `wallpaper`, `dynamic` (§5.2) | D |
| `source.seedColor` | colour | absent | `#RRGGBB` | kind `seed` without valid seed → behaves as `fixed`, warning |
| `source.lockedRoles` | array of role names | `[]` | unique subset of the 9 roles | invalid items dropped |
| `pureBlack` | boolean | `false` | — | D |
| `light`, `dark` | palette object | built-in palettes below | each role a colour | per role: D (built-in role value of the same variant) |

**Colour value:** `#RRGGBB`, opaque sRGB, hex digits case-insensitive. Alpha is not
allowed in colours — the order ambiguity between `#AARRGGBB` (Android) and `#RRGGBBAA`
(CSS) is a known source of errors, so transparency is expressed only by
`wallpaper.dim` and `wallpaper.backgroundOpacity`.

**Roles** (the same 9 in each variant):

| Role | Used for | Built-in light | Built-in dark |
|---|---|---|---|
| `background` | Home/list backdrop (composited over wallpaper, §4.3) | `#FAFAF7` | `#121212` |
| `surface` | Search field, menus, sheets, dialogs (always opaque) | `#EDECE6` | `#1F1F1F` |
| `textPrimary` | The *onBackground* role: app labels, clock, favourites | `#1B1B1A` | `#ECECEA` |
| `textSecondary` | Muted onBackground role: date, section letters, hints, index | `#57564F` | `#B0B0AA` |
| `accent` | Selection, cursor, active index letter, switches, focus ring | `#2F5D8A` | `#8DB4DE` |
| `onAccent` | Text/icons drawn on `accent` | `#FFFFFF` | `#0B2239` |
| `divider` | Hairlines between rows/sections (decorative) | `#D6D4CC` | `#33332F` |
| `highlight` | Opaque row background when pressed/selected/focused | `#E3E1D9` | `#2B2B28` |
| `scrim` | Colour laid over the wallpaper at alpha `wallpaper.dim` | `#FFFFFF` | `#000000` |

**Pure black** (`pureBlack: true`): when the dark variant is in effect, `background` and
`scrim` resolve to `#000000`; other roles unchanged. Ignored in the light variant.
Inferred (APK-020) as a capability.

### 4.3 `wallpaper` (transparency and dimming)

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `show` | boolean | `false` | `true` = launcher window is translucent and shows the system wallpaper | D |
| `dim` | number | `0.5` | `0.0..0.95` (alpha of `scrim` over the wallpaper) | C |
| `backgroundOpacity` | number | `0.0` | `0.0..1.0` (alpha of `background` over the dimmed wallpaper) | C |
| `image.sha256` | string | absent | 64 lowercase hex; must name an image in the app's image store (§10.3) | missing image → ignored, warning |
| `image.mediaType` | enum | absent | `image/png`, `image/jpeg`, `image/webp` | D (sniffed) |

Effective background, bottom to top: wallpaper `W` → `scrim` at alpha `dim` →
`background` at alpha `backgroundOpacity`. With `show: false` the background is drawn
opaque and `dim`/`backgroundOpacity` are ignored. A theme's `image` is **never** set as
the system wallpaper silently: applying a theme that carries one asks "Also set this
theme's wallpaper?". On "Yes", Strata hands the image to the system wallpaper activity with
`WallpaperManager.getCropAndSetWallpaperIntent(contentUri)` through its `FileProvider`
(fallback `ACTION_ATTACH_DATA`), and the user confirms there; Strata holds no
`SET_WALLPAPER` permission and never calls `setStream`/`setBitmap` (D-009, ANDROID_PLATFORM
§12). Inferred (APK-026: dim-wallpaper toggle) as a capability.

### 4.4 `typography`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `family` | fontRef | `{ "kind": "system" }` | see below | D (system font) |
| `clockFamily` | fontRef | absent → same as `family` | see below | D |
| `scale` | number | `1.0` | `0.80..1.50` (multiplies the system font scale, never replaces it) | C |
| `labelWeight` | integer | `400` | `100..900`, multiple of 100 | round, C |
| `headingWeight` | integer | `500` | same | round, C |
| `clockWeight` | integer | `300` | same | round, C |
| `letterSpacingEm` | number | `0` | `-0.05..0.20` | C |
| `labelCase` | enum | `asIs` | `asIs`, `lower`, `upper`, `title` (locale-sensitive case mapping) | D |
| `smallTextUsesSystemFont` | boolean | `false` | when `true`, text below 14 sp and truncated long labels use the system font for legibility | D |

**fontRef** object:

| `kind` | Extra fields | Meaning | If unavailable |
|---|---|---|---|
| `system` | — | Platform default sans-serif (`FontFamily.Default`) | — |
| `bundled` | `id` (`^[a-z0-9][a-z0-9-]{0,39}$`) | A font shipped in the APK under the SIL Open Font License, from the bundled-font catalogue | unknown `id` → system font, warning, value preserved |
| `user` | `sha256` (64 hex), optional `displayName` | A user-supplied TTF/OTF in the app's font store, addressed by content hash | missing file → system font, warning "Font not on this device", value preserved |

The **bundled-font catalogue** (IDs, files, licence texts) is owned by the visual spec
(T-05) and must be chosen independently from OFL sources (e.g. Google Fonts / upstream
repos) with each licence file shipped in the APK and shown in *Settings → Licences*.
IDs are stable forever once released. Weights a font file lacks are synthesised by the
platform. Inferred (APK-022: bundled OFL fonts, user fonts, size steps, system-font
readability option) as capabilities; our range is continuous rather than stepped.

### 4.5 `list` (density and spacing)

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `density` | enum | `comfortable` | `compact` (row 48 dp), `comfortable` (56 dp), `spacious` (64 dp) | D |
| `rowHeightDp` | integer | absent (use density) | `48..96`; the minimum keeps the 48 dp touch-target rule | C |
| `horizontalPaddingDp` | integer | `16` | `0..48` | C |
| `itemSpacingDp` | integer | `0` | `0..24` | C |
| `sectionHeaders` | boolean | `true` | letter headers between groups | D |
| `showDividers` | boolean | `false` | — | D |

Rows grow beyond their nominal height when the effective font scale requires it; text is
never clipped to honour a row height (NFR-THEME-10).

### 4.6 `icons`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `visible` | boolean | `true` | show icons in the app list at all | D |
| `sizeDp` | integer | `32` | `20..64` | C |
| `shape` | enum | `system` | `system` (device mask), `circle`, `squircle`, `roundedSquare`, `square` | D |
| `cornerRadiusPercent` | integer | `25` | `0..50`, used by `roundedSquare` only | C |
| `pack` | object or `null` | `null` | `{ package, displayName?, fallback }` | invalid → `null` (system icons), preserved |
| `pack.package` | string | — | Java package name, ≤ 255 chars | see above |
| `pack.fallback` | enum | `system` | `system`, `monochrome` — used for apps the pack does not cover, and for everything if the pack is not installed | D |
| `tint` | enum | `none` | `none`; `monochrome` (adaptive icon monochrome layer, `AdaptiveIconDrawable.getMonochrome()` API 33+, else desaturated) drawn in `textPrimary`; `accent` (same, drawn in `accent`) | D |
| `legacyIconPlate` | boolean | `true` | place non-adaptive icons on a `surface`-coloured plate in the chosen shape | D |
| `labelsVisible` | boolean | `true` | app-name labels next to icons. When `false`, list rows show the icon only, keep the label as their accessible name, and stay ≥ 48 dp; if `visible` is also `false`, labels are forced on | D |

Shapes apply only to `AdaptiveIconDrawable` icons and to plated legacy icons; icon-pack
bitmaps are drawn as supplied. A missing `pack` app shows a one-time notice "This theme
uses the icon pack *displayName*, which is not installed", with a button that opens the
store listing via `market://details?id=<package>` (an intent built only from the package
name; the app itself fetches nothing). Inferred (APK-023, APK-024) as capabilities.

### 4.7 `clock` and date

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `visible` | boolean | `true` | — | D |
| `style` | enum | `numeric` | `numeric` (single line), `numericStacked` (hours over minutes), `words` (spelled-out, localised; falls back to `numeric` for locales without a words formatter), `analogMinimal` (two hands, no numerals) | D |
| `hourFormat` | enum | `system` | `system` (`DateFormat.is24HourFormat`), `h12`, `h24` | D |
| `showSeconds` | boolean | `false` | — | D |
| `sizeSp` | integer | `56` | `24..128` (scaled by `typography.scale` and the system font scale, capped so the clock never exceeds 40 % of screen width per glyph line) | C |
| `alignment` | enum | `start` | `start`, `center`, `end` (mirrored in RTL) | D |
| `date.visible` | boolean | `true` | — | D |
| `date.skeleton` | string | `EEEEdMMMM` | `^[yMLdEc]{1,16}$`, a CLDR skeleton localised with `DateFormat.getBestDateTimePattern(locale, skeleton)`, so ordering and punctuation are always locale-correct | D |
| `date.sizeSp` | integer | `16` | `12..32` | C |

Inferred (APK-026: clock styles, date toggle) as capabilities. The four style IDs are
our own and are defined visually by T-05.

### 4.8 `favorites`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `textScale` | number | `1.3` | `0.8..2.0` relative to list label size | C |
| `alignment` | enum | `start` | `start`, `center`, `end` | D |
| `verticalPosition` | enum | `bottom` | `top`, `center`, `bottom` (thumb reach) | D |
| `showIcons` | boolean | `false` | — | D |
| `showLabels` | boolean | `true` | if both `showIcons` and `showLabels` are false, labels are forced on | D |
| `spacingDp` | integer | `8` | `0..32` | C |

Which apps are favourites, and their order, is *not* theme data (§6). Inferred
(APK-026: hide favourites' labels) as a capability.

### 4.9 `search`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `position` | enum | `bottom` | `top`, `bottom`, `hidden` (search still opens by gesture/keyboard) | D |
| `style` | enum | `pill` | `pill` (filled `surface`, full radius), `box` (filled, `cornerRadiusDp`), `underline` (no fill, `textSecondary` 1 dp line) | D |
| `cornerRadiusDp` | integer | `24` | `0..28` (`box` only) | C |
| `showHint` | boolean | `true` | hint text in `textSecondary` | D |

Search providers and behaviour are global settings (§6). Proposed.

### 4.10 `alphabetIndex`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `visibility` | enum | `always` | `always`, `listOnly` (hidden on the home page, shown in the full list), `never` | D |
| `side` | enum | `end` | `end`, `start` (mirrored in RTL) | D |
| `glyphs` | enum | `letters` | `letters`, `dots` (dots expand to letters while touched) | D |
| `textSizeSp` | integer | `12` | `10..18` | C |
| `showBubble` | boolean | `true` | large letter bubble while scrubbing (`surface` / `textPrimary`) | D |

Index letters use `textSecondary`; the active letter uses `accent`. The index stays
usable through TalkBack (each section is an accessibility action) even when `never`.
Inferred (APK-026) as a capability.

### 4.11 `animation`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `durationScale` | number | `1.0` | `0.0..2.0`; `0` = no theme animations | C |
| `listEntrance` | enum | `fade` | `none`, `fade`, `slide` | D |

**Effective duration** = base duration × `durationScale` × system animator duration scale
(`Settings.Global.ANIMATOR_DURATION_SCALE`). If the system scale is 0 or
`ValueAnimator.areAnimatorsEnabled()` returns false ("Remove animations"), all theme
animations are off regardless of the theme — this cannot be overridden by a theme or by
a global setting (NFR-THEME-06). Proposed.

### 4.12 `haptics`

| Field | Type | Default | Allowed | On invalid |
|---|---|---|---|---|
| `mode` | enum | `followSystem` | `followSystem` (use `View.performHapticFeedback` constants, which respect the system touch-feedback setting), `off` | D |

The global "Disable all haptics" switch (§6) always wins. Inferred (APK-029) as a capability.

---

## 5. Colour sources and modes

### 5.1 Variant selection (`colors.mode`)
| Mode | Variant chosen |
|---|---|
| `light` / `dark` | Always that variant. |
| `system` | `dark` when `Configuration.uiMode` night bit is set, else `light`; re-evaluated on configuration change. |
| `wallpaper` | API 31+: `dark` unless `WallpaperColors.getColorHints()` contains `HINT_SUPPORTS_DARK_TEXT`. API 27–30: `light` if the relative luminance of `WallpaperColors.getPrimaryColor()` > 0.5, else `dark`. API 26, or `getWallpaperColors()` returns null (some live wallpapers): behave as `system`. Re-evaluated on `WallpaperManager.OnColorsChangedListener`. |

Inferred (APK-020: follow device / follow wallpaper / always on / always off).
A combined "device-and-wallpaper" mode is not in v1 (could be added in a minor).

### 5.2 Palette derivation (`colors.source.kind`)
| Kind | Palette | Fallback chain |
|---|---|---|
| `fixed` | `colors.light` / `colors.dark` as written (missing roles from built-in) | — |
| `seed` | Generated from `seedColor` with a tonal-palette algorithm (HCT colour space, "tonal spot" style), implemented in `:core:theme` with no runtime library (ARCHITECTURE §15). If code is ported from the Apache-2.0 `material-color-utilities` project, its licence and NOTICE ship in Settings → Licences | invalid seed → `fixed` |
| `wallpaper` | Generated the same way from `WallpaperColors.getPrimaryColor()` (API 27+) | API 26 / null colours → `fixed` |
| `dynamic` | System Material dynamic colour scheme (`dynamicLightColorScheme` / `dynamicDarkColorScheme`, API 31+) | API < 31 → `wallpaper` → `fixed` |

Mapping from a Material-style scheme to our roles (Proposed): `background` ← surface;
`surface` ← surfaceContainerHigh; `textPrimary` ← onSurface; `textSecondary` ←
onSurfaceVariant; `accent` ← primary; `onAccent` ← onPrimary; `divider` ← outlineVariant;
`highlight` ← surfaceContainerHighest; `scrim` ← black (dark) / white (light).

Roles listed in `lockedRoles` keep the theme's fixed value even for derived kinds. The
fixed `light`/`dark` palettes of a derived theme therefore serve two purposes: locked
roles and the fallback. `pureBlack` is applied after derivation. Derived palettes are
**always** passed through contrast enforcement (§7.5), because the user did not choose
those colours. Inferred (APK-021: auto/wallpaper-and-system/standard palettes) as a
capability.

---

## 6. Global settings vs per-theme settings

Rule (Proposed): a setting is per-theme if it changes **how things look**; it is global if
it changes **what is there, how it behaves, or an accessibility guarantee**. Inferred
(APK-024: global vs per-theme split) as a principle. Keys, types and defaults of every
global setting: ARCHITECTURE §4.2 (the single settings catalogue).

| Global (stored in app settings, never in theme files) | Per-theme (in the file) |
|---|---|
| Active theme pointer; last-known-good pointer | All of §4 |
| Appearance override: `followTheme` (default) / `system` / `light` / `dark` (overrides `colors.mode`) | |
| Enforce minimum contrast: on (default) / off (§7.5) | |
| Disable all haptics (master off) | |
| Favourite apps and order; hidden apps; renamed apps; per-app icon overrides (categories: later, not v1) | |
| Search options (hidden apps in search, recents, contacts), gestures, sorting, home layout behaviour (web search hand-off: v1.x, no engine choice) | |
| Status-bar visibility, rotation, notification badges | |
| Language; the system font scale and animator scale (read, never stored) | |

Per-app icon overrides are global (they belong to the user's app inventory, not to a
look) and survive theme switches.

---

## 7. Contrast and accessibility (WCAG 2.2 AA)

### 7.1 Targets
| # | Foreground | Background | Minimum | WCAG basis |
|---|---|---|---|---|
| P1 | `textPrimary` | effective background | 4.5:1 | 1.4.3 text |
| P2 | `textSecondary` | effective background | 4.5:1 | 1.4.3 (date, hints and index letters are small text) |
| P3 | `accent` | effective background | 3:1 | 1.4.11 UI component / state |
| P4 | `textPrimary` | `surface` | 4.5:1 | 1.4.3 (search text, menus) |
| P5 | `textSecondary` | `surface` | 4.5:1 | 1.4.3 (hint text is not exempt) |
| P6 | `accent` | `surface` | 3:1 | 1.4.11 (cursor, focus, switches in sheets) |
| P7 | `textPrimary` | `highlight` | 4.5:1 | 1.4.3 (pressed/selected rows) |
| P8 | `onAccent` | `accent` | 4.5:1 | 1.4.3 |

`divider` is decorative and exempt. Large text (≥ 24 sp, or ≥ 18.66 sp bold) would only
need 3:1, but because the clock and favourites use the same roles as small text, we hold
those roles to 4.5:1 — simpler and stricter. Monochrome/accent-tinted icons are covered
by P1/P3. Every pair is checked for each variant the theme can show (`light` and/or `dark`
per `mode`), and for the dark variant also with `pureBlack` toggled on.

### 7.2 Computation
- Relative luminance and contrast ratio per WCAG 2.x (sRGB linearisation with the 0.04045
  threshold; ratio = (L1 + 0.05) / (L2 + 0.05)).
- Compositing is source-over in **gamma-encoded sRGB**, matching how Android/Compose
  blend by default.
- **Effective background** `E(W) = over(background, backgroundOpacity, over(scrim, dim, W))`.
  With `show: false`, `E = background`.

### 7.3 Static (wallpaper-independent) check
Each channel of `E(W)` is affine in the wallpaper colour `W`, and luminance is monotonic
in each channel, so over all possible wallpapers `L(E)` spans exactly
`[L(E(black)), L(E(white))]`. For a foreground with luminance `Lf`: if `Lf` lies inside
that interval the worst-case ratio is 1:1 (fail); otherwise it is the smaller ratio at
the two ends. A theme passing this check is **"AA on any wallpaper"** (badge in the UI).
Built-in presets and all sample themes must pass it (NFR-THEME-05).

### 7.4 Runtime (current-wallpaper) check
On API 27+ with non-null `WallpaperColors`, the app also evaluates P1–P3 with `W` set to
each of the primary/secondary/tertiary wallpaper colours and reports **"AA on current
wallpaper"**. This is an approximation: the platform does not expose per-pixel
wallpaper data without extra permissions, so a busy photo may still have small
low-contrast areas. The UI says so in the help text. Proposed.

### 7.5 Auto-fix policy
- **Never silently rewrite the user's file.** Fixes either happen at render time
  (enforcement) or are applied explicitly in the editor (fix button) and then saved.
- **Enforcement** (global, default on): during resolution, for each failing pair, in the
  order P1, P2, P4, P5, P7, P3, P6, then P8:
  1. Move the foreground's tone (HCT tone, hue and chroma kept) away from the background
     by bisection until the ratio ≥ target + 0.1.
  2. If the foreground hits tone 0 or 100 and still fails, and the failure is against the
     effective background with `show: true`, raise `dim` in 0.05 steps up to 0.95, then
     raise `backgroundOpacity` in 0.1 steps up to 1.0.
  3. Otherwise move the background role's tone the opposite way.
  4. P8 last: adjust `onAccent` (towards black or white, whichever is reached first).
  Enforcement uses the runtime check (§7.4) when wallpaper colours exist, else the static
  check. Users may switch enforcement off (it is their device); the warning stays visible.
- **Editor fix button:** applies the same algorithm to the edited theme and records it as
  one undoable step.

### 7.6 Warning UI
- Each colour control in the editor shows its live ratio and a pass/fail chip
  ("4.8 : 1 AA" / "2.9 : 1 — hard to read"), with pass/fail conveyed by text and icon,
  not colour alone.
- A banner in the editor lists failing pairs in plain words ("Secondary text on the
  search bar is hard to read") with "Fix" and "Show me" (scrolls preview to the spot).
- The import summary (§10.4) shows "Readable on any wallpaper / on current wallpaper /
  Has low-contrast colours (will be adjusted while 'Enforce contrast' is on)".
- TalkBack announces contrast status changes politely, not on every slider tick.

### 7.7 Other accessibility rules
Honour system font scale up to 200 % without clipping (rows grow); minimum touch target
48 dp regardless of density; no information by colour alone; reduced motion per §4.11;
`start`/`end` mirror in RTL; label case transforms never change TalkBack's spoken text.

---

## 8. Validation pipeline

### 8.1 Stages (all off the main thread; each stage bounded)
1. **Read with cap.** Stream at most `256 KiB + 1` bytes; exceeding → reject
   ("Theme file is too large").
2. **Decode.** Strict UTF-8 (BOM tolerated). Invalid bytes → reject.
3. **Parse.** RFC 8259 strict: no comments, no trailing commas, no NaN/Infinity,
   **duplicate keys rejected** (avoids parser-differential ambiguity). Errors report
   line and column. Limits enforced during parsing: depth ≤ 16, ≤ 128 keys per object,
   ≤ 256 array items, ≤ 2048 JSON values total, any string ≤ 4096 UTF-16 units, number
   literals ≤ 32 chars. Exceeding → reject.
4. **Version gate.** `schemaVersion` checks of §10.5; then migrate (§10.6).
5. **Structural validation.** Validate against `spec/theme.schema.json` in *lenient* mode:
   `additionalProperties` violations become "unknown key" warnings; every other
   violation is resolved with the field's **On invalid** policy (§4). The result is a
   typed `ThemeDocument` plus a list of `Issue(path, severity, code, message)`.
6. **Reference check.** Fonts, images and icon pack exist? Missing → warnings (fallbacks
   per §4).
7. **Contrast check** (§7). Produces warnings, never rejection.
8. **Commit** (only for imports/saves): atomic write and index update (§11).

Severity: `error` (file rejected), `fixed` (a value was clamped/defaulted/regenerated),
`warning` (rendered with fallback), `info`. Rejection happens **only** for the cases
marked R; every other problem degrades gracefully.

### 8.2 Error messages (user-facing, localised)
"This file isn't a Strata theme." · "This theme was made with a newer version of Strata
Launcher. Update the app to use it." · "The file is damaged (line 12, column 4)." ·
"The file is too large." · "The theme bundle contains unexpected files." · "A font in this
bundle couldn't be read." Messages never echo raw file content beyond 64 characters.

### 8.3 Unknown fields and round-trip
The in-memory model keeps, for every object, the unknown members (including `x-*`) as
raw JSON in original order, and for every field resolved by **D** its original raw value.
On save the writer emits known fields (edited value, or the preserved original if the
user did not touch it), then unknown members. Guarantee: `parse(write(parse(f)))` is
JSON-equal to `parse(f)` for any valid `f` the user did not edit (NFR-THEME-07). The
`x-` namespace is reserved for extensions by anyone; v1 never interprets it. Unknown
non-`x-` keys are reserved for future minors of this schema.

---

## 9. Security and robustness

| Threat | Control |
|---|---|
| Resource exhaustion (huge files, deep nesting, zip bombs) | Limits in §8.1 and §10.3; byte counting on actual decompressed bytes, not header claims |
| Network exfiltration / tracking | Theme data contains no URLs that are ever fetched; `$schema` and any string are inert. The app declares no `INTERNET` use for theming (D-007). Store links are built from package names only and opened by the user |
| Code execution | No scripting, no expressions, no reflection on names from the file, no dynamic class loading; enum strings map through fixed tables |
| Malicious font files | Font validation (§10.3.3) before the platform font loader sees them; size cap; fonts stored under hash names in app-private storage |
| Malicious images | Bounds-first decode, pixel caps, re-encode on import (also strips EXIF/GPS metadata) |
| Path traversal ("zip slip") | Entry names are matched against a fixed allow-list regex and never used as paths; files are written under names derived from verified hashes. (Android 14+'s `ZipPathValidator` is an additional, not the primary, defence) |
| Parser differentials | Duplicate keys rejected; one parser used for validation and loading |
| Boot loops | §12 recovery |
| Privacy | Theme files contain no device data; exported themes include only what the user sees in the export summary; `author` is optional and never auto-filled |

---

## 10. Import and export

### 10.1 Entry points
- **In-app import:** `ACTION_OPEN_DOCUMENT` (Storage Access Framework) with the SAF types of
  §3.1; content sniffed (§3).
- **From other apps:** `ThemeImportActivity` handling `ACTION_VIEW` and `ACTION_SEND` for
  the types of §3.1, `content:` URIs only. `text/plain` is not matched here.
  The import screen always shows a summary first; nothing is applied automatically.
- **Export:** `ACTION_CREATE_DOCUMENT` with suggested name `<name>.strata-theme.json`
  (or `.zip`).
- **Share:** `ACTION_SEND` with a `FileProvider` URI to a temporary export copy in
  `cacheDir/share/`, `FLAG_GRANT_READ_URI_PERMISSION`; the copy is deleted after 24 h or
  on next start.
Inferred (APK-024: import, export/share) as capabilities.

### 10.2 Single JSON
Export writes the stored document (with preserved unknowns) and refreshes `modifiedAt`.
If the theme references user fonts or a wallpaper image, the export dialog offers
"Include fonts and wallpaper (.zip)"; JSON-only export keeps the hash references, and the
importing device falls back with a warning if it lacks them. Bundled OFL fonts are
referenced by ID, never embedded.

### 10.3 Bundle `.strata-theme.zip`

#### 10.3.1 Layout
```
manifest.json
theme.json                              (a .strata-theme.json document)
fonts/<sha256>.ttf | fonts/<sha256>.otf (0–4 files)
images/<sha256>.png | .jpg | .webp      (0–1 file)
```
Allowed entry-name regex (exact, case-sensitive):
`^(manifest\.json|theme\.json|fonts/[0-9a-f]{64}\.(ttf|otf)|images/[0-9a-f]{64}\.(png|jpg|webp))$`.
Directory entries are ignored; any other entry → reject.

#### 10.3.2 Manifest
```json
{
  "bundleFormat": "strata-theme-bundle",
  "bundleVersion": 1,
  "theme": { "path": "theme.json", "sha256": "<64 hex>", "bytes": 2048 },
  "files": [
    { "path": "fonts/<64 hex>.otf", "role": "font", "sha256": "<64 hex>", "bytes": 81234 },
    { "path": "images/<64 hex>.webp", "role": "wallpaper", "sha256": "<64 hex>", "bytes": 912345 }
  ]
}
```
Rules: manifest ≤ 16 KiB, same JSON parsing limits as §8.1; `bundleVersion > 1` → reject
("newer version"); every listed file must exist, its SHA-256 and byte count must match,
and the hash in its file name must equal its content hash; every non-manifest entry must
be listed; `theme.json` must reference only listed fonts/images.

#### 10.3.3 Limits and checks
| Item | Limit / check |
|---|---|
| Archive | ≤ 24 MiB on disk; ≤ 8 entries; ≤ 24 MiB total decompressed (counted while streaming); per-entry decompressed/compressed ratio ≤ 200; no encrypted entries; no ZIP64 |
| `theme.json` | all of §8 |
| Font | ≤ 4 MiB; first 4 bytes `00 01 00 00`, `OTTO` or `true` (collections `ttcf`, WOFF/WOFF2 rejected); table directory sane (≤ 64 tables, every table inside the file, required `cmap head hhea hmtx maxp name`); then `Typeface.Builder(file).build()` (no fallback set) must return non-null and measure a test string with non-zero width. Otherwise reject the bundle ("A font in this bundle couldn't be read") |
| Image | ≤ 16 MiB; decode bounds first; ≤ 8192 px per side and ≤ 40 MP; decode with sampling to ≤ display size × 2. A WebP source that is already within these limits and carries no EXIF/XMP/ICC metadata chunks is stored byte-for-byte. Any other source is re-encoded to WebP (lossless for PNG sources, quality 90 for JPEG and lossy WebP sources); the hash is recomputed after re-encoding and the theme reference rewritten. Because the stored file is then a stripped WebP, a second import of an exported bundle keeps the same bytes and hash |
| Extraction | stream into `cacheDir/import/<random>/` using hash-derived names only; move into stores only after all checks pass; temp dir deleted in `finally` |

User fonts in the font store are content-addressed and shared between themes; an
orphaned font/image is garbage-collected when no stored theme references it.
Exporting a bundle with a user font shows: "Only share fonts whose licence allows it."
User-added fonts outside bundles come from the font manager (*Add font* →
`ACTION_OPEN_DOCUMENT`, types per §3.1), with the same validation. Inferred (APK-022: user
fonts, invalid-font error) as a capability.

### 10.4 Import flow and conflicts
1. Validate (§8 / §10.3). 2. Show summary: name, author, preview thumbnail, warnings
(missing icon pack with store button, missing fonts, contrast status, "N unknown settings
from a newer version will be kept"). 3. Choose **Add** or **Add and apply**.

Conflicts (Proposed):
| Situation | Behaviour |
|---|---|
| Same `id` as a stored user theme, identical canonical JSON | "Already in your themes" → offer *Apply*; nothing written |
| Same `id`, different content | Ask: **Replace** (keeps `id`; old version kept for undo until next start), **Keep both** (import gets a new UUID, `basedOn` = original id), **Cancel** |
| Same `id` as a built-in preset | Always *Keep both* with new UUID (built-ins are immutable) |
| Same `name`, different `id` | Imported copy is displayed as "Name (2)", "(3)", …; the stored `name` is updated accordingly |

### 10.5 Version handling
App constants: `MAJOR = 1`, `MINOR = 0`.
| File | Result |
|---|---|
| `schemaVersion == 1`, `schemaMinor <= 0` | Normal import |
| `schemaVersion == 1`, `schemaMinor > 0` | Import; unknown keys/enum values preserved and listed as "newer settings kept"; written back with `schemaMinor = max(file, app)` |
| `schemaVersion > 1` | Reject with the "newer version" message. If such a file arrives via backup restore it is kept on disk untouched and listed as "Needs app update" (not applicable, not deleted) |
| `schemaVersion < 1` / missing | Reject ("not a Strata theme") |
Inferred (APK-024: newer-version import failure) as a capability.

Minor-version contract: a minor may only **add** optional fields or enum values whose
absence or replacement by the default renders acceptably. Anything else is a new major.

### 10.6 Migrations
- A chain of pure functions `migrate_N_to_N+1(JsonObject): JsonObject`, applied on the raw
  tree before structural validation; each preserves unknown keys and is unit-tested with
  frozen fixture files (input + expected output) kept forever.
- v1 has no predecessors; the chain is empty but the mechanism ships in v1 so v2 is not a
  special case.
- Stored files are migrated in memory on load and rewritten only on the next save; before
  the first rewrite of a migrated file, the original is kept as `<id>.v<N>.bak` until the
  next successful boot after the rewrite.

---

## 11. Persistence

### 11.1 Layout (all app-private, `Context.filesDir`)
```
themes/user/<id>.strata-theme.json        user and imported themes
themes/fonts/<sha256>.ttf|.otf            content-addressed user fonts
themes/images/<sha256>.webp               content-addressed theme wallpapers
themes/lkg.strata-theme.json              copy of the last-known-good theme
datastore/theme_index.pb                  Proto DataStore index
assets/themes/*.strata-theme.json         (in the APK) built-in presets, read-only
```

### 11.2 Index (Proto DataStore)
`ThemeIndex { repeated Entry entries; string activeThemeId; string lastKnownGoodId;
bool applyInProgress; int64 applyStartedAt; }` with
`Entry { id, name, origin (BUILTIN|USER|IMPORTED), fileSha256, createdAt, modifiedAt,
lastAppliedAt, contrastStatus, needsAppUpdate }`. The index is a cache over the files:
if it is corrupt (`ReplaceFileCorruptionHandler`) or disagrees with the directory, it is
rebuilt by scanning `themes/user/` at startup. Files are the source of truth.

### 11.3 Writes
- Theme files: `androidx.core.util.AtomicFile` (write temp → `fsync` → rename). Order:
  write file, then update index; startup reconciliation repairs a crash between the two.
- No limit on the number of saved themes other than free storage (contrast APK-025).
  Writes fail cleanly with "Not enough storage" and leave existing data intact.
- Delete: removes the entry and file after a 5-second undo snackbar; deleting the active
  theme switches to the last-known-good (or Strata Default) first. Built-ins cannot be
  deleted but can be hidden from the picker.
- Editing a built-in preset transparently creates a user copy on the first change
  ("Paper (copy)", `basedOn` = built-in id).

### 11.4 Backup
- Auto Backup / device transfer (`dataExtractionRules` API 31+, `fullBackupContent` ≤ 30):
  include `themes/user/`, `themes/fonts/`, `themes/lkg.strata-theme.json`, and the
  DataStore index; exclude `themes/images/` from cloud backup (size quota; the system
  backs up its own wallpaper) but include it for device-to-device transfer; exclude
  `cacheDir`. Proposed.
- On restore, every file goes through §8 again (fonts re-validated); invalid files are
  quarantined to `themes/quarantine/`, never deleted silently.
- The app-wide settings backup format (separate task) embeds themes as these same JSON
  documents, so there is only one theme format.

---

## 12. Boot-time loading and invalid-theme recovery

1. On process start, read the index and the active theme file on a background
   dispatcher; the first frame uses Strata Default *only if* loading has not finished
   within 150 ms (normally it has; budget NFR-THEME-02). Theme switches cross-fade
   (respecting §4.11).
2. If the active file is missing, unreadable, rejected, or `needsAppUpdate`: load
   `lkg.strata-theme.json`; if that also fails, use the compiled-in Strata Default.
   Show a non-blocking banner: "Your theme couldn't be loaded, so the default theme is
   shown. [Details] [Choose theme]".
3. **Last-known-good** is updated only after a theme has been applied and the app has
   rendered for 10 s and survived until the next `onStop` without crashing; then the
   active file is copied to `lkg.strata-theme.json`.
4. **Crash-loop guard:** `applyInProgress = true` is written before applying a theme and
   cleared after the first successful frame. If a start finds it still true (twice
   within 5 minutes), the app boots with Strata Default, marks the theme
   "Caused a problem — not applied", and keeps the file. **Precedence:** the process-level
   `boot_attempts` counter (ARCHITECTURE §10) is checked first; if it triggers safe mode,
   safe mode already uses Strata Default, and this guard only decides which theme to mark
   as the offender (the one with `applyInProgress` still set). It never starts its own
   safe mode.
5. Any exception inside theme resolution is caught at the resolution boundary, logged
   locally (no upload, D-007), and handled as step 2. Theme code never throws past this
   boundary (NFR-THEME-01).

---

## 13. Editor and live preview

- **Layout:** live preview (top or side on wide screens) rendering the real home and list
  composables with the user's real apps (or a sample list toggle), clock, favourites,
  search bar and index; controls grouped as in §4 below. Preview toggles: light/dark
  variant, wallpaper on/off, "show contrast problems" overlay.
- **Latency:** each control change re-renders the preview within one frame for discrete
  controls and continuously while dragging sliders (NFR-THEME-08). Expensive work
  (palette derivation, font loading) is cached by input hash.
- **Colour control:** hex field, hue/chroma/tone sliders, swatches from the current
  wallpaper colours and from the dynamic scheme, live contrast chip (§7.6).
- **Create from image:** pick an image with the system photo picker (no storage
  permission), extract a seed colour, generate a `seed` palette; optionally keep the
  image as the theme's wallpaper. Inferred (APK-010: start theme from image) as a
  capability.
- **Undo/redo:** per-session stack of immutable theme snapshots, ≥ 100 steps; a slider
  drag or a text-field edit session is coalesced into one step. Undo survives
  configuration changes; the in-progress draft is autosaved to
  `themes/drafts/<id>.json` every 2 s and offered for recovery after process death.
- **Reset:** per field ("Reset to default"), per group, and whole theme ("Reset to
  *basedOn* preset" or "Reset to Strata Default"); each reset is one undo step.
- **Save / Apply / Discard:** Save writes the preset; Apply makes it active; leaving with
  unsaved changes asks Save / Discard / Keep editing.
- **Advanced:** "Edit as JSON" view with the same validator, inline issue list, and
  "Copy JSON" — supports the human-editable goal.
- Every editor control has a TalkBack label and value; sliders support keyboard/D-pad
  steps.
Inferred (APK-024: create from scratch, edit current theme) as capabilities.

---

## 14. Built-in presets and sample themes

Built-in presets for v1 (Proposed): **Strata Default** (compiled-in), plus the five
original sample themes below, shipped as assets with fixed IDs.

| File | Name | Idea | Mode / source | Wallpaper |
|---|---|---|---|---|
| `paper.strata-theme.json` | Paper | Warm off-white page, ink text, rust accent, text-only list | light / fixed | hidden |
| `graphite.strata-theme.json` | Graphite | Cool dark grey, teal accent, small rounded icons, 24 h clock | dark / fixed | shown, dim 0.6, background 70 % |
| `midnight-oled.strata-theme.json` | Midnight OLED | Pure black, amber accent, monochrome icons, lowercase, minimal motion, haptics off | dark / fixed + pureBlack | hidden |
| `dusk.strata-theme.json` | Dusk | Palette from the wallpaper; violet fixed fallbacks; spelled-out clock | wallpaper / wallpaper (scrim locked) | shown, dim 0.75 |
| `harbor.strata-theme.json` | Harbor | Sparse file (most fields default) using system dynamic colour; sea-blue fallbacks; demonstrates an `x-` key | system / dynamic | hidden |

### 14.1 Verification results (2026-10-29)
The checker `tools/check_themes.py` (Python 3.13, `jsonschema` 4.26.0; run as
`python3 -I tools/check_themes.py spec/theme.schema.json spec/sample-themes/*.json`) validated each file against `spec/theme.schema.json` (strict mode,
after `check_schema` on the schema itself) and computed P1–P8 per §7 using the static
worst-case wallpaper method (§7.3), for every variant the theme can show and, for dark
variants, also with `pureBlack` forced on. It also checked the all-defaults theme and six
negative cases (schemaVersion 2, bad UUID, 5-digit colour, scale 3, `user` font without
hash, unknown top-level key) which the schema must reject, plus one `x-` extension case
it must accept.

| Theme | Schema | Variants checked | Lowest text ratio (≥ 4.5) | Lowest UI ratio (≥ 3.0) | Result |
|---|---|---|---|---|---|
| Strata Default (all defaults) | valid | light, dark, dark+pureBlack | 6.23 (textSecondary on surface, light) | 5.81 (accent on surface, light) | PASS |
| Paper | valid | light | 5.85 (textSecondary on surface) | 6.22 (accent on surface) | PASS |
| Graphite | valid | dark, dark+pureBlack | 6.11 (textSecondary on effective bg, dark) | 6.05 (accent on effective bg, dark) | PASS |
| Midnight OLED | valid | dark+pureBlack | 7.70 (textSecondary on surface) | 10.82 (accent on surface) | PASS |
| Dusk | valid | light, dark, dark+pureBlack | 5.07 (textSecondary on effective bg, light) | 3.60 (accent on effective bg, light) | PASS |
| Harbor | valid | light, dark, dark+pureBlack | 6.60 (textSecondary on surface, light) | 5.72 (accent on surface, light) | PASS |
| Negative / extension cases | 6 rejected, 1 accepted as expected | — | — | — | PASS |

104 pair checks, 0 failures. For Dusk and Harbor these results cover the **fallback**
palettes; their derived palettes depend on the device and are covered at runtime by
enforcement (§7.5). The checker's algorithm is fully specified in §7.2–7.3 so the
implementation's unit tests can reproduce these numbers.

---

## 15. Requirements

**Priority:** every FR-THEME and NFR-THEME requirement below is **P0 for v1** (D-004: free theming is the core differentiator). Milestone scheduling is in IMPLEMENTATION_ROADMAP (M3, M4, M8, M9).

Acceptance criteria (AC) are written to be testable; test IDs (`T-THEME-nn`) are assigned
by T-06.

### 15.1 Functional

| ID | Requirement | Acceptance criteria | Label |
|---|---|---|---|
| FR-THEME-01 | All theming features are free and ungated. | No billing dependency in the build; no code path checks entitlement for any §4 field; any number of themes can be saved, imported and applied; no theme field marks "premium" content. | Proposed (D-004; deliberate contrast to APK-025) |
| FR-THEME-02 | Themes are single JSON documents with `schemaVersion`, `.strata-theme.json`, `application/json`. | A file containing only `schemaVersion`, `id`, `name` imports and renders as Strata Default; the writer output validates against `theme.schema.json`. | Proposed (D-006) |
| FR-THEME-03 | Nine colour roles in separate light and dark palettes, with per-role defaults. | Omitting any role uses the built-in value of that variant; each role visibly changes its documented UI element in the preview. | Proposed |
| FR-THEME-04 | Appearance modes `system`, `light`, `dark`, `wallpaper`, plus a global override. | Switching system dark mode flips `system` themes without restart; `wallpaper` mode follows §5.1 on API 26, 27–30 and 31+ (instrumented tests per level). | Inferred (APK-020) |
| FR-THEME-05 | Pure-black option for the dark variant. | With `pureBlack`, background and scrim render `#000000` in dark only; no effect in light. | Inferred (APK-020) |
| FR-THEME-06 | Colour sources `fixed`, `seed`, `wallpaper`, `dynamic` with fallback chain and locked roles. | On API 30 a `dynamic` theme uses wallpaper colours; on API 26 it uses fixed palettes; locked roles never change after a wallpaper change; derived palettes always pass P1–P8 after enforcement. | Inferred (APK-021) |
| FR-THEME-07 | Wallpaper visibility, dim and background opacity. | Effective background equals §7.2 formula within ±1/255 per channel in a screenshot test with a solid test wallpaper. | Inferred (APK-026) |
| FR-THEME-08 | Typography: system / bundled OFL / user font by hash; scale 0.8–1.5 multiplied by system scale; weights; letter spacing; label case. | Each field changes rendered text measurably; missing user font falls back with a warning; system font scale 2.0 × theme scale 1.5 renders without clipping. | Inferred (APK-022) |
| FR-THEME-09 | Option to render small/long text in the system font. | With the option on, text < 14 sp uses `FontFamily.Default` while other text uses the theme font. | Inferred (APK-022) |
| FR-THEME-10 | Icons: visibility, size, shape mask, legacy icon plate, labels, icon pack with fallback. | Shapes apply to adaptive icons; legacy icons get a plate when `legacyIconPlate`; uninstalling the pack switches to `pack.fallback` without restart and shows the one-time notice with store button. | Inferred (APK-023) |
| FR-THEME-11 | Monochrome/accent icon tint using the platform monochrome layer. | API 33+ uses `getMonochrome()` when present; otherwise a desaturated icon tinted with the role colour. | Proposed |
| FR-THEME-12 | Clock and date presentation (style, hour format, seconds, size, alignment, date skeleton). | Date text equals `DateFormat.getBestDateTimePattern(locale, skeleton)` output for 5 test locales incl. one RTL; alignment mirrors in RTL. | Inferred (APK-026) |
| FR-THEME-13 | Favourites presentation (scale, alignment, vertical position, icons, labels, spacing). | Labels are forced on when both icons and labels are off; changing the theme never changes which apps are favourites. | Inferred (APK-026) |
| FR-THEME-14 | Search-bar presentation (position, style, radius, hint). | `hidden` still allows opening search by its gesture; styles render per §4.9. | Proposed |
| FR-THEME-15 | Alphabet-index appearance (visibility incl. list-only, side, glyphs, size, bubble). | `listOnly` hides it on home only; index remains reachable via TalkBack actions when `never`. | Inferred (APK-026) |
| FR-THEME-16 | Animation duration scale and entrance style, honouring the system animator scale. | With system "Remove animations" on, no theme animation runs for any `durationScale`; effective duration = base × theme × system. | Proposed |
| FR-THEME-17 | Haptics `followSystem` / `off`, with a global master off. | With master off, no haptic call is made for any theme. | Inferred (APK-029) |
| FR-THEME-18 | Per-field invalid-value policies (clamp / default / generate / truncate / reject) as in §4. | A fuzzed corpus of ≥ 10 000 mutated sample files never crashes; each mutation class yields the documented policy and an `Issue` entry. | Proposed |
| FR-THEME-19 | Unknown keys, `x-` keys and unknown enum values are preserved on round-trip. | `parse(write(parse(f)))` is JSON-equal to `parse(f)` for all samples and a fixture with nested unknowns and a future enum value. | Proposed |
| FR-THEME-20 | Contrast checking per §7 with live chips, banner, auto-fix and global enforcement. | Unit tests reproduce §14.1 ratios to 2 decimals; a failing palette shows the banner; "Fix" makes all pairs pass in one undoable step; enforcement output passes P1–P8. | Proposed |
| FR-THEME-21 | Import a single JSON via SAF, `ACTION_VIEW` and `ACTION_SEND`, with content sniffing and a summary before applying (types per §3.1). | Files labelled `application/octet-stream` import through all three paths and `text/plain` through SAF; nothing is applied until the user confirms. | Inferred (APK-024) |
| FR-THEME-22 | Import/export `.strata-theme.zip` bundles with manifest and hashes. | Bundles produced by export re-import on a clean install with identical fonts/images; any hash mismatch, unlisted entry or bad name rejects the bundle. | Proposed |
| FR-THEME-23 | Export to a document and share via `FileProvider`. | Exported JSON validates against the schema; share target receives a readable `content:` URI; temp copies are purged. | Inferred (APK-024) |
| FR-THEME-24 | Version gate: newer major rejected with a clear message; newer minor imported with unknowns preserved. | Fixture with `schemaVersion: 2` shows the "newer version" message; fixture with `schemaMinor: 5` and unknown keys imports and round-trips them. | Inferred (APK-024) |
| FR-THEME-25 | Missing icon pack notice with store link built from the package name. | Notice shows once per theme apply; the button fires an intent for `market://details?id=<package>`; no network request is made by the app. | Inferred (APK-024) |
| FR-THEME-26 | Import conflict handling by `id` and `name` (§10.4). | All four rows of the conflict table are covered by tests; built-in IDs are never overwritten. | Proposed |
| FR-THEME-27 | Preset library: built-ins, unlimited user themes, duplicate, edit, delete with undo, hide built-ins. | 500 stored themes list and apply correctly; delete is undoable for 5 s; editing a built-in creates a copy. | Inferred (APK-024) |
| FR-THEME-28 | Active-theme pointer, last-known-good and invalid-theme recovery (§12). | Corrupting the active file, the index, or both still boots to a usable launcher with the banner; crash-loop guard triggers after two failed starts. | Proposed |
| FR-THEME-29 | Theme editor with live preview, undo/redo, per-field/group/theme reset, JSON view, draft recovery. | ≥ 100 undo steps; slider drags coalesce; killing the process mid-edit offers the draft on return. | Inferred (APK-024) |
| FR-THEME-30 | Create a theme from an image via the photo picker. | Picking an image yields a `seed` theme whose palette passes P1–P8; no storage permission is requested. | Inferred (APK-010) |
| FR-THEME-31 | Global vs per-theme split per §6. | Switching themes never changes favourites, hidden apps, renames, per-app icons, search providers or gestures. | Inferred (APK-024) |
| FR-THEME-32 | Migration framework with frozen fixtures and `.bak` safety copy. | A synthetic v0→v1 test migrator passes fixture tests; the `.bak` is removed only after a successful boot. | Proposed |
| FR-THEME-33 | Theme data included in Auto Backup / device transfer per §11.4, re-validated on restore. | Restored invalid files land in quarantine; valid ones appear in the library. | Proposed |
| FR-THEME-34 | Theme wallpaper images applied only with explicit confirmation, by handing the image to the system wallpaper activity (`getCropAndSetWallpaperIntent`, fallback `ACTION_ATTACH_DATA`); no `SET_WALLPAPER` permission (D-009). | Declining starts no wallpaper intent; accepting starts exactly one with a `content:` `image/*` URI and a read grant; the merged manifest has no `SET_WALLPAPER`. | Proposed |

### 15.2 Non-functional

| ID | Requirement | Acceptance criteria | Label |
|---|---|---|---|
| NFR-THEME-01 | Theme problems never crash the launcher or block boot. | Fuzz suite (FR-THEME-18) plus corrupted-storage tests: 0 crashes, launcher usable in all cases. | Proposed |
| NFR-THEME-02 | Load and resolve fast. | Parse + validate + resolve a 256 KiB theme ≤ 30 ms, a typical (< 8 KiB) theme ≤ 5 ms, on the T-06 reference low-end device (Macrobenchmark); cold start not delayed > 150 ms by theming. | Proposed |
| NFR-THEME-03 | Atomic, consistent persistence. | Killing the process at random points during 1 000 save/import cycles leaves every theme either old or new, and the index reconcilable. | Proposed |
| NFR-THEME-04 | Input limits and security controls of §8.1, §9, §10.3 enforced. | Test corpus: oversize, deep, duplicate-key, zip-bomb, zip-slip names, bad fonts, huge images — all rejected with the documented message and no partial writes. | Proposed |
| NFR-THEME-05 | Built-in presets are AA on any wallpaper; derived/enforced palettes are AA. | CI runs the §7.3 check over `assets/themes/` and fails on any pair below target. | Proposed |
| NFR-THEME-06 | Accessibility honoured beyond the theme's control. | System animator scale 0, font scale 2.0, TalkBack and RTL pass the T-06 accessibility suite for every built-in preset. | Proposed |
| NFR-THEME-07 | Round-trip fidelity. | Property-based test over generated documents with random unknown members: JSON equality after round-trip. | Proposed |
| NFR-THEME-08 | Live preview responsiveness. | Discrete changes visible within 1 frame (≤ 16 ms on 60 Hz) of state change; slider drags hold ≥ 55 fps on the reference device. | Proposed |
| NFR-THEME-09 | Deterministic resolution. | `resolve` is a pure function; identical inputs produce identical `ResolvedTheme` (snapshot tests). | Proposed |
| NFR-THEME-10 | Layout robustness. | No clipped text at any combination of density, `rowHeightDp`, theme scale and system font scale within ranges (screenshot matrix). | Proposed |
| NFR-THEME-11 | No network use by the theme system. | Theme module has no network dependency; StrictMode/network-blocked test passes all theme flows. | Proposed (D-007) |
| NFR-THEME-12 | Clean-room independence of format and content. | T-07 review confirms no key names, values, file types or assets derived from the original app. | Proposed |

---

## 16. Open questions (proposed for `research/UNRESOLVED.md`)
- **TQ-1** Bundled OFL font catalogue (which fonts, total APK size budget) — owner T-05.
- **TQ-2** *Closed (T-08):* API levels are registered in ANDROID_PLATFORM (DOC-043
  `getMonochrome` API 33, DOC-046 `getColorHints` API 31, DOC-049 dynamic colour API 31).
  `ZipPathValidator` (API 34) is only a secondary defence (§9) and needs no DOC entry.
- **TQ-3** Whether to add an optional label text-shadow/halo to improve legibility on busy
  wallpapers (affects §7.4 guarantees) — owner T-05.
- **TQ-4** Final schema `$id` domain once the product name is chosen (U-006).
