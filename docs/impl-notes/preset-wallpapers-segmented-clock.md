# Preset wallpaper sets and segmented clock styles

Owner request 2026-11-25. Both are generic engine features (Proposed); the public presets use neither.

## Preset wallpaper sets (FR-THEME-36, THEME_SYSTEM §14.2)
- A built-in preset may ship `<preset>.wallpapers.txt` (`<sha256> <label>` lines) and `images/<sha256>.webp` in
  `assets/themes/`. `SyncThemeAssets` checks lists and images and fails the build on any problem (`ThemeAssetLogic`,
  T-THEME-60 build half); `ThemeRepositoryTest.presetWallpaperSets_packagedAndVerified` checks the packaged assets.
- `BuiltInThemeSource` gained `wallpapers`, `image` and `imageNames` (defaults: none). `ThemeLibrary` reads images from
  the image store, then from the preset images, always verifying the hash; preset images count as present for the
  resolver. `wallpaperChoices(id)` gives the set of the preset the theme is or is `basedOn`, else its own image.
- Library UI: after applying a theme with several images the wallpaper prompt becomes a picker (thumbnails decoded
  off the main thread at 1/8 size, labels as names); a preset with a set has "Set wallpaper…" in its menu. The
  hand-off is the FR-THEME-34 one; no permission is added (T-THEME-61).

## Clock styles `segmented` and `countdownBoard` (FR-THEME-37, VISUAL_SPEC §9.4)
- `:core:ui` `SevenSegment` / `SegmentedDigits`: own geometry, unlit bars at 10 %, locale digits mapped by value,
  scaled to fit, never mirrored (T-THEME-58).
- Home: `countdownBoard` adds a "T−" label in `accent` and turns the date into an upper-case status line with an
  `accent` lamp. The time is spoken as before. Centring follows T-THEME-56 (T-THEME-59). The editor preview and labels
  (en, de) know both values. Added to schema 1.0 before the v1 release; `schemaMinor` stays 0.

## Tests
`SevenSegmentTest`, `SegmentedClockTest` (16 cases), `ThemeAssetLogicTest`, `PresetWallpaperTest`,
`PresetWallpaperPickerTest`; `tools/mirror/test-sync.sh` covers the second overlay (12 cases).
