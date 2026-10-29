# Strata Launcher — Visual Specification

Task T-05 · Owner: agent:product · 2026-10-29 · Status: review
Every value here is **Proposed** (our design) unless it cites evidence. Theme-controlled
values name their THEME_SYSTEM field. This file defines layout and the non-theme tokens
only and does not restate the schema. Behaviour: `spec/INTERACTION_SPEC.md`.
Requirements: `spec/PRODUCT_SPEC.md`.

**Clean room.** No layout measurement, colour, font, clock design or artwork of the
original was available or used (U-002, D-003). §12 states how we stay visually distinct.

---

## 1. Visual hierarchy (home, in order of emphasis)
1. **Favourites**: the primary action surface, largest labels (`favorites.textScale`, default 1.3 × list label).
2. **Clock**: the largest glyphs, but `textPrimary` at the clock weight (default 300, light), so it reads as ambient.
3. **Search pill**: always visible (unless `search.position = hidden`), the only filled shape on the home page.
4. **Date / secondary text**: `textSecondary`.
5. **Alphabet index**: the quietest element (`textSecondary`, 12 sp) until touched.
One focal action per region; no decorative chrome, cards or shadows on the home page.

## 2. Layout regions (compact width, portrait)
```
┌──────────────────────────────┐ ← status-bar inset (safeDrawing top)
│ HEADER   clock / date        │  top margin 24 dp; height = content (clock ≤ 30 % of window height)
│          or widget area      │  widget height: provider min … 50 % of window height
├──────────────────────────────┤
│ FLEX SPACE (weight 1)        │  absorbs leftover height; ≥ 16 dp
├──────────────────────────────┤
│ FAVOURITES  ≤ 12 rows        │  verticalPosition=bottom → block sits on top of the search pill
│ "All apps ›" row             │  same style as favourites, `textSecondary`
├──────────────────────────────┤
│ SEARCH PILL                  │  height 52 dp, side margin 16 dp, 12 dp above the nav inset
└──────────────────────────────┘ ← navigation-bar / gesture inset (safeDrawing bottom)
  ▐ ALPHABET INDEX on `side` edge (FR-THEME-15): visual width 24 dp, touch width 48 dp
```
- Horizontal content padding: `list.horizontalPaddingDp` (default 16). Rows reserve 48 dp
  on the index side when the index is visible, so labels never sit under the index.
- `favorites.verticalPosition = top|center` moves the block below the header or centres it.
  `search.position = top` puts the pill under the header.
- **All-apps list (S-LIST):** full window, top inset + 8 dp, then an optional search field
  (same pill), sections and list-end rows; 96 dp bottom padding so the last row clears the
  gesture area.
- **Sheets:** Material-style modal bottom sheet, max width 640 dp, top corner radius 28 dp,
  `surface` colour, 24 dp inner padding, drag handle 32 × 4 dp.

## 3. Typography scale
The base is the list label size `L = 16 sp × typography.scale` (FR-THEME-08); the system
font scale is applied on top by `sp`. Line height ≈ 1.3 × size. Never fixed-height text
containers (NFR-THEME-10).

| Token | Size | Weight | Role | Used for |
|---|---|---|---|---|
| `type.clock` | `clock.sizeSp` (56) | `clockWeight` (300) | textPrimary | clock |
| `type.favorite` | L × `favorites.textScale` (≈ 21 sp) | `labelWeight` | textPrimary | favourites |
| `type.title` | 22 sp | `headingWeight` | textPrimary | sheet / screen titles |
| `type.label` | L (16 sp) | `labelWeight` | textPrimary | list rows, search results |
| `type.date` | `clock.date.sizeSp` (16) | 400 | textSecondary | date line |
| `type.section` | 13 sp | `headingWeight`, +0.06 em tracking | textSecondary | section letters |
| `type.body` | 14 sp | 400 | textPrimary | settings, dialogs |
| `type.caption` | 12 sp | 400 | textSecondary | secondary row line, badges, hints |
| `type.index` | `alphabetIndex.textSizeSp` (12) | 500 | textSecondary / accent | index letters |
| `type.bubble` | 40 sp | 600 | textPrimary on surface | index bubble |
Settings, sheets and dialogs always use the system font. The theme font applies to home, list
and search surfaces (and `smallTextUsesSystemFont`, FR-THEME-09, applies there).

## 4. Fonts
### 4.1 Rules
`fontRef` per THEME_SYSTEM §4.4. Missing weights are synthesised. Fonts are never
downloaded (NFR-PRIV-01).

### 4.2 Bundled OFL catalogue (answers THEME_SYSTEM TQ-1)
Chosen independently, for legibility range, script coverage and size. Each ships its OFL
licence file, listed in Settings → About → Licences (FR-SET-06). The licence must be
re-verified from upstream at integration (T-06 build check). Budget ≤ 3 MB total (NFR-PERF-07),
using variable fonts where available and the Latin + Latin-Ext + Cyrillic + Greek subsets.
| `id` (stable) | Family | Character |
|---|---|---|
| `inter` | Inter | neutral grotesque, UI default alternative |
| `atkinson-hyperlegible` | Atkinson Hyperlegible | high-legibility (low-vision) |
| `ibm-plex-sans` | IBM Plex Sans | technical humanist |
| `lexend` | Lexend | wide, reading-ease |
| `source-serif-4` | Source Serif 4 | serif option |
| `jetbrains-mono` | JetBrains Mono | monospace option |
Scripts outside the subsets fall back to the system font per glyph (platform fallback).

## 5. Spacing and density tokens
| Token | Value | Use |
|---|---|---|
| `space.xxs` | 2 dp | badge offset |
| `space.xs` | 4 dp | icon–badge gap |
| `space.s` | 8 dp | favourites spacing default (`favorites.spacingDp`), between icon and label inside chips |
| `space.m` | 12 dp | icon–label gap in rows |
| `space.l` | 16 dp | screen side padding (`list.horizontalPaddingDp`), sheet item padding |
| `space.xl` | 24 dp | header top margin, sheet padding |
| `space.xxl` | 32 dp | section gap in settings |
| `row.compact / comfortable / spacious` | 48 / 56 / 64 dp min height | `list.density` (THEME_SYSTEM §4.5); `rowHeightDp` overrides |
| `section.header` | 32 dp min height, 8 dp top gap | `list.sectionHeaders` |
| `radius.s / m / l / full` | 8 / 12 / 28 dp / 50 % | chips / cards & widget clip / sheets / pill |
Rows grow with the font scale; the minimum height is never below 48 dp.

## 6. Colour roles → UI elements
All colours come from the resolved theme (THEME_SYSTEM §4.2). No hard-coded colours except
the platform scrim for system dialogs.
| Element | Role |
|---|---|
| Home / list backdrop | effective background `E(W)` (scrim at `dim` + `background` at `backgroundOpacity`) |
| Clock, favourites, row labels | `textPrimary` |
| Date, section letters, index, hints, secondary row line, "All apps" | `textSecondary` |
| Search pill, sheets, menus, dialogs, index bubble, monogram tile | `surface` (+ `textPrimary` / `textSecondary`) |
| Pressed / focused / keyboard-selected row | `highlight` behind `textPrimary` |
| Focus ring (2 dp, 4 dp offset), active index letter, cursor, switches, "new" marker, badge dot | `accent` |
| Text/count on badge, on accent buttons | `onAccent` |
| Dividers (if `showDividers`), sheet handle | `divider` |
| Work badge, paused state | system badged icon; paused rows at 60 % α (text still ≥ 4.5:1 is enforced by keeping the label in `textPrimary` and dimming only the icon) |
| Status/nav bar icon tint | light/dark chosen from the effective background luminance (`isAppearanceLightStatusBars`) |
**TQ-3 decision:** no text halo or shadow in v1. Legibility on busy wallpapers comes from
`dim` and contrast enforcement (THEME_SYSTEM §7.5). A halo can be added later as an additive
`schemaMinor` field (proposed to T-04).

## 7. Icons
| Context | Size |
|---|---|
| List row | `icons.sizeDp` (default 32 dp, range 20–64) |
| Favourites (`showIcons`) | `round(icons.sizeDp × 1.25)` (default 40 dp) |
| Search result | same as list row |
| Sheet header | 56 dp |
| Widget picker preview | provider preview fit inside 160 × 120 dp, app icon 24 dp |
| Badge dot | 8 dp, at the icon's top-end corner (or after the label if icons are hidden) |
| Count badge | min 16 dp tall, `type.caption` bold, pill shape, max "99+" |
| Work / private badge | system-badged icon; with icons hidden, a 16 dp glyph after the label |
Shape, tint, legacy plate and pack: FR-THEME-10/11. Bitmaps are decoded at the exact pixel size
(no up-scaling); the monogram tile is the fallback (INTERACTION_SPEC §8).

## 8. Responsive rules
Width classes per `WindowSizeClass` (DOC-067).
### 8.1 Compact (< 600 dp)
One column, layout §2. Landscape on phones (if rotation is allowed): header and favourites
sit side by side (header start 40 %, favourites end 60 %), the clock is capped at 25 % of
height, and the widget is hidden if the height is < 360 dp.
### 8.2 Medium (600–839 dp)
Home: as compact with side padding 32 dp; favourites max width 480 dp. List: two columns
(FR-LIST-07), 24 dp gutter, a section header spans both columns.
### 8.3 Expanded (≥ 840 dp)
Two panes. The **start pane** (360–420 dp) holds header, widget, favourites and search pill.
The **end pane** holds the all-apps list, always visible in two columns, with the index on its
outer edge. Search results replace the end pane. Opening S-LIST is then a no-op focus move.
### 8.4 Foldables and windowing
Use `WindowLayoutInfo`/`FoldingFeature`: with a separating vertical hinge, the start pane goes
on one side and the list on the other, and no text or touch target crosses the hinge.
Tabletop (horizontal hinge): header and widget above the fold, favourites, search and list
below it. Multi-window: follow the width class of the window, not the display.
### 8.5 RTL
Mirror: horizontal layout order, index side (`end` → left), chevrons, sheet alignment, clock
`alignment` start/end, badge position. Do not mirror: clock digits, analog clock direction,
app icons, media-like glyphs. Section order follows the locale's alphabet (ICU).
### 8.6 Insets and edge-to-edge
Always edge-to-edge (P-6). The backdrop draws under the system bars. Content uses
`safeDrawing` padding. The index uses `systemGestures` awareness per INTERACTION_SPEC §4.2. The
pill and list end respect the `ime` inset. Display cutouts: header content avoids the cutout
rect; the backdrop does not. With three-button navigation, a translucent scrim from
`enableEdgeToEdge` is kept.

## 9. Home components
### 9.1 Search pill
`search.style` (pill / box / underline) per FR-THEME-14. Contents: leading search glyph
(20 dp, `textSecondary`), hint "Search apps" (`type.label`, `textSecondary`), trailing
overflow (opens S-HMENU, 48 dp target). In S-SEARCH the same shape becomes the field (shared
element), with a clear button when there is text.
### 9.2 Favourites
Text-first rows (`showIcons=false` default): label in `type.favorite`, min height 48 dp, row
spacing `favorites.spacingDp`, alignment `favorites.alignment`. Badges sit after the label.
The "All apps ›" row ends the block.
### 9.3 Alphabet index
- Idle: a column of letters (or dots, `glyphs=dots`) in `type.index`, `textSecondary` at
  70 % α, vertically centred in the available height, letter pitch = available height / letter
  count, clamped to 14–28 dp. If there are too many buckets for 14 dp, middle letters fold
  into "•" separators.
- Active (touch down): the column widens to 32 dp on a `surface` rounded track
  (`radius.full`, 90 % α). The letter under the finger turns `accent` and scales 1.3×, and its
  neighbours scale 1.15×.
- Bubble (`showBubble`): a 64 × 64 dp rounded-square tile (`radius.l` 20 dp) that tracks the finger's
  y and sits 24 dp inward from the track, never under the finger.
- Empty buckets: 35 % α, not focusable.
- The touch area is 48 dp wide (extending inward) over the full track height, minus the
  INTERACTION_SPEC §4.2 margins.
### 9.4 Clock and date styles (our own designs; THEME_SYSTEM §4.7 IDs)
| `style` | Design |
|---|---|
| `numeric` | One line `HH:MM` in `type.clock`, tabular figures (`fontFeatureSettings "tnum"`), no AM/PM glyph in 24 h. In 12 h, the period marker is appended at 30 % size with baseline alignment. |
| `numericStacked` | Hours above minutes, each line `type.clock`, line height 0.9 ×, minutes in `textSecondary`. Our layering motif. |
| `words` | Time spelled out in the locale (e.g. "twenty past nine"), up to 3 lines at 0.5 × `clock.sizeSp`, the last word in `accent`. Falls back to `numeric` where there is no words formatter. |
| `analogMinimal` | Circle of diameter 2 × `clock.sizeSp` dp. No numerals, no ticks except one 12 o'clock dot (`accent`). Hour hand 4 dp, minute hand 2 dp, `textPrimary`, round caps. Seconds hand only if `showSeconds`. |
The date sits below the clock, aligned with it, 4 dp gap, in `type.date`. The clock carries a
content description with the spoken time, and updates on `ACTION_TIME_TICK` (seconds:
1 Hz only while visible and resumed).

## 10. Motion tokens
| Token | Duration | Easing | Use |
|---|---|---|---|
| `motion.instant` | 50 ms | linear | opacity swaps, press state |
| `motion.short` | 150 ms | standard (0.2, 0, 0, 1) | row collapse, bubble fade, chip changes |
| `motion.medium` | 250 ms | emphasized decelerate (0.05, 0.7, 0.1, 1) enter / accelerate (0.3, 0, 0.8, 0.15) exit | search, list, sheets |
| `motion.long` | 350 ms | standard | theme cross-fade, list entrance (`animation.listEntrance`) |
Effective duration = token × `animation.durationScale` × the system animator scale (FR-THEME-16).
**Reduced motion** ("Remove animations" or scale 0): every transition is an instant cut.
Predictive-back progress still follows the finger (user-driven, not an animation) but with no
spring overshoot. Nothing loops or auto-plays on home at any setting. Spatial moves are capped
at 10 % of the screen dimension so a 0.5 scale stays calm.

## 11. Contrast and touch targets
- Contrast: THEME_SYSTEM §7 (pairs P1–P8, static and runtime checks, enforcement on by
  default). Additional non-theme pairs checked by the same function: focus ring vs effective
  background ≥ 3:1 (P3 covers it), badge `onAccent` on `accent` ≥ 4.5:1 (P8), the paused-row
  icon is decorative and the label stays compliant.
- No state is shown by colour alone: active index letter = colour + scale, focus = ring, hidden = text tag, paused = tag.
- **Touch targets ≥ 48 × 48 dp** for every interactive element (rows, index letters through the
  48 dp-wide strip, pill buttons, sheet items, resize handles, badges are not separate targets).
  Adjacent targets are ≥ 8 dp apart or merged.

## 12. Distinctiveness (avoid confusion with the original's trade dress)
The original's documented look: a start-aligned favourites list under a top clock/date,
with empty space above to lower content (PUB-008); an edge alphabet strip, including an
invisible strip on the opposite edge (PUB-011); inline notification text previews
instead of dots (PUB-020); swipe-right pop-ups (PUB-008); an optional floating button at
the bottom end (PUB-005); vendor icon families, a dot icon pack, curated themes and
wallpapers (PUB-039/040/043/045). We share the generic list-launcher idea (not protectable),
but differ in every specific:
| Aspect | Strata |
|---|---|
| Name, launcher icon, store art | Own name (placeholder pending U-006). The icon is three offset horizontal bands (a "strata" motif), drawn from scratch. |
| Home composition | Favourites anchored **at the bottom** above an always-visible **search pill**. The header floats at the top with flexible space between them. |
| Search entry | A visible pill plus the gesture (the original relies on the gesture). |
| Notifications | **Dot / count badges** by default; text preview only as an opt-in. |
| Per-app actions | Long-press sheet only; no horizontal row gestures or pop-ups. |
| Alphabet index | One visible index on a chosen side; an active track with a **rounded-square bubble offset inward**; dot glyph option; no invisible opposite strip. |
| Clock | Our four styles (§9.4), including the stacked hours/minutes "strata" style. No copied clock designs. |
| Section headers | Explicit letter headings in the list, with an optional hairline. |
| Floating button | None. |
| Themes, palettes, fonts, wallpapers | Only our own presets (THEME_SYSTEM §14) and the OFL catalogue in §4.2; no bundled wallpapers in v1. |
| Copy and terminology | Our own wording ("All apps", "Hidden apps", "Home menu"); no reuse of the original's feature names. |
| File types | `.strata-theme.json`, `.strata-theme.zip`, `.strata-backup.json` (D-006). |
T-07 should check screenshots of the built app against this table before release.
