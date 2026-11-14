# Decision note: app monochrome layer before the Strata glyph (suggested D-020)

Status: **Proposed** (owner decision, 2026-11-14). To be filed in `research/DECISIONS.md` as D-020
(D-016…D-019 are reserved by the M9 proposals).

## Decision
The icon source precedence of THEME_SYSTEM §4.6 (FR-THEME-35) changes from
override → pack → Strata glyph → platform monochrome → desaturated to

1. per-app override
2. icon-pack mapping
3. platform monochrome layer (`AdaptiveIconDrawable.getMonochrome()`, API 33+, usable layers only)
4. Strata glyph
5. desaturated icon

Steps 3-5 still apply only to tinted icons; `tint: none` (with `pack.fallback: system`) is unchanged and shows the
app's normal icon ("System icon (untinted)").

**Rationale.** The developer's own themed icon is the most faithful monochrome rendition of the app. The glyph
set fills the gaps: apps without a usable layer, and every app on API < 33.

## Layer sanity check
Some apps ship a monochrome layer that is effectively empty (transparent) or a full-bleed opaque square. Such a
layer is treated as absent, so the glyph (else the desaturated icon) is used.
- `MonochromeLayers.isUsable` (`:platform:icons`) draws the layer once into a 36 × 36 px bitmap.
- Both tests look only at the visible area: the inner 2/3 of the layer, the part the adaptive mask shows.
- **Empty:** fewer than 0.5 % of the visible pixels have alpha above 32.
- **Block:** at least 95 % of the visible pixels have alpha above 224.
- **Failure:** a layer that throws while drawing counts as unusable, so the glyph fallback still applies.
- It runs inside the existing pipeline when the icon's sources are decided: on the render dispatcher, or on the I/O
  dispatcher for the coverage tool. It never runs on main. The result is baked into the cached bitmap, so it runs once
  per rendered icon.
- These thresholds are deliberately crude; they only catch broken layers, not ugly ones.

## Consequences
- `IconSourceResolver` and `AndroidIconProvider.sources` follow the new order. The coverage tool (FR-SET-07) shares
  the resolver, so its sources, counts (listed in precedence order, `IconSource` enum order) and highlights follow
  automatically.
- **Cache:** the cache key's pipeline version goes from `v2` to `v3` (`AndroidIconProvider.PIPELINE_VERSION`,
  ARCHITECTURE §9), so every L1/L2 bitmap drawn under the old order is a miss after the update.
- **Tests:** T-THEME-54 (new order, glyph + layer on API 33 / 32, empty and opaque layers, no layer) and T-SET-09
  (counts in the new order) are updated.
- **Spec:** THEME_SYSTEM §4.6, FR-THEME-11, FR-THEME-35, PRODUCT_SPEC FR-SET-07 and ARCHITECTURE §9 / §9.1 are updated.
