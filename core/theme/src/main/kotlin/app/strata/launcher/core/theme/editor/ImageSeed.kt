package app.strata.launcher.core.theme.editor

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.color.Hct
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.color.TonalSpot
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastEnforcer
import app.strata.launcher.core.theme.model.BuiltInPalettes
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.WallpaperImage
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.resolve.ThemeResolver
import kotlin.math.abs
import kotlin.math.min

/**
 * Seed colour from an image (THEME_SYSTEM §13 "Create from image"). Our own method: pixels are binned at 5 bits per
 * channel, each occupied bin is converted to HCT once, and chromatic, mid-tone bins vote for 10° hue sectors weighted
 * by pixel count × chroma (neighbouring sectors count half). The seed is the strongest bin of the winning sector. An
 * image without colour (greys only) gives its most common colour.
 */
object SeedExtractor {
    private const val SECTORS = 36
    private const val MIN_CHROMA = 8.0
    private const val CHROMA_CAP = 60.0
    private const val MIN_TONE = 15.0
    private const val MAX_TONE = 92.0

    /** [pixels] are `0xAARRGGBB`; nearly transparent pixels are ignored. Null for an empty or fully transparent image. */
    fun seed(pixels: IntArray): Int? {
        val counts = HashMap<Int, IntArray>() // bin -> [count, sumR, sumG, sumB]
        for (p in pixels) {
            if ((p ushr 24) < MIN_ALPHA) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val acc = counts.getOrPut(bin) { IntArray(4) }
            acc[0]++
            acc[1] += r
            acc[2] += g
            acc[3] += b
        }
        if (counts.isEmpty()) return null

        data class Bin(val argb: Int, val count: Int, val hct: Hct)
        val bins =
            counts.values.map { a ->
                val argb = Rgb.of(a[1] / a[0], a[2] / a[0], a[3] / a[0])
                Bin(argb, a[0], Hct.fromArgb(argb))
            }
        val votes = DoubleArray(SECTORS)
        val chromatic = bins.filter { it.hct.chroma >= MIN_CHROMA && it.hct.tone in MIN_TONE..MAX_TONE }
        for (b in chromatic) votes[sector(b.hct.hue)] += b.count * min(b.hct.chroma, CHROMA_CAP)
        if (chromatic.isEmpty()) return bins.maxWith(compareBy({ it.count }, { it.argb })).argb
        val smoothed =
            DoubleArray(SECTORS) { i ->
                votes[i] +
                    0.5 * (votes[(i + 1) % SECTORS] + votes[(i + SECTORS - 1) % SECTORS])
            }
        val best = smoothed.indices.maxWith(compareBy({ smoothed[it] }, { -it }))
        val centre = best * (360.0 / SECTORS) + 180.0 / SECTORS
        return chromatic
            .filter { hueDistance(it.hct.hue, centre) <= 360.0 / SECTORS }
            .maxWith(compareBy({ it.count * min(it.hct.chroma, CHROMA_CAP) }, { it.argb }))
            .argb
    }

    private fun sector(hue: Double): Int = ((hue.mod(360.0)) / (360.0 / SECTORS)).toInt().coerceIn(0, SECTORS - 1)

    private fun hueDistance(
        a: Double,
        b: Double,
    ): Double {
        val d = abs(a - b).mod(360.0)
        return min(d, 360.0 - d)
    }

    private const val MIN_ALPHA = 128
}

/** Writes a seed palette into a theme tree (create-from-image, §13; FR-THEME-30). */
object ImageTheme {
    /**
     * Sets `colors.source` to `seed` with [seed], and writes the tonal-spot palettes for both variants, already passed
     * through contrast enforcement (static check, the theme's own `wallpaper`), into `colors.light` / `colors.dark`.
     * They are the fallback and locked-role values (§5.2), so the file reads well even where the seed is not used.
     * [image] (optional) becomes `wallpaper.image`; it is never set as the system wallpaper silently (§4.3).
     */
    fun apply(
        tree: JsonObject,
        seed: Int,
        wallpaper: WallpaperSpec,
        image: WallpaperImage? = null,
    ): JsonObject {
        var t = TreeEdits.set(tree, FieldPath("colors", "source", "kind"), JsonString(SourceKind.SEED.wire))
        t = TreeEdits.set(t, FieldPath("colors", "source", "seedColor"), JsonString(Rgb.toHex(seed)))
        for (v in Variant.entries) {
            val p = palette(seed, v, wallpaper)
            val base = BuiltInPalettes.of(v)
            for (r in ColorRole.entries) {
                val path = FieldPath("colors", v.wire, r.wire)
                t =
                    if (p[r] ==
                        base[r]
                    ) {
                        TreeEdits.remove(t, path)
                    } else {
                        TreeEdits.set(t, path, JsonString(Rgb.toHex(p[r])))
                    }
            }
        }
        if (image != null) {
            t =
                TreeEdits.set(
                    t,
                    FieldPath("wallpaper", "image"),
                    JsonObject(
                        "sha256" to JsonString(image.sha256),
                        "mediaType" to
                            JsonString(
                                (image.mediaType ?: app.strata.launcher.core.theme.model.ImageMediaType.WEBP).wire,
                            ),
                    ),
                )
        }
        return t
    }

    /** The enforced tonal-spot palette of [seed] for [variant]; the dark one also passes with pure black. */
    fun palette(
        seed: Int,
        variant: Variant,
        wallpaper: WallpaperSpec,
    ): Palette {
        val dark = variant == Variant.DARK
        var p = ContrastEnforcer.enforce(ThemeResolver.map(TonalSpot.scheme(seed, dark), dark), wallpaper).palette
        if (dark) {
            val black = setOf(ColorRole.BACKGROUND, ColorRole.SCRIM)
            repeat(2) {
                val pb = ContrastEnforcer.enforce(Contrast.applyPureBlack(p), wallpaper, locked = black).palette
                p = pb.copy(background = p.background, scrim = p.scrim)
                p = ContrastEnforcer.enforce(p, wallpaper).palette
            }
        }
        return p
    }
}
