package app.strata.launcher.core.theme

import app.strata.launcher.core.theme.color.MaterialRoles
import app.strata.launcher.core.theme.color.Rgb
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.model.BuiltInPalettes
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.ColorsSpec
import app.strata.launcher.core.theme.model.Palette
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.ThemeDocument
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.resolve.AppearanceOverride
import app.strata.launcher.core.theme.resolve.DynamicSchemeInput
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.core.theme.write.ThemeWriter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** T-THEME-02, -04, -06, -07, -25, -49 (unit halves; the screenshot halves need :core:ui, M4 integration). */
class ResolverTest {
    private val light = PlatformInputs(apiLevel = 34, systemDark = false)
    private val dark = PlatformInputs(apiLevel = 34, systemDark = true)
    private val magenta = 0xFFFF00FF.toInt()

    private fun resolve(
        doc: ThemeDocument,
        p: PlatformInputs,
        g: ThemeGlobals = ThemeGlobals(),
    ): ResolvedTheme = ThemeResolver.resolve(doc, p, g).theme

    private fun withoutId(t: ResolvedTheme) = t.copy(themeId = "")

    @Test
    fun `T-THEME-02 a file with only schemaVersion, id and name resolves exactly like Strata Default`() {
        val parsed = parseOk(minimal())
        assertThat(parsed.issues).isEmpty()
        assertThat(parsed.document.copy(id = StrataDefault.ID, name = StrataDefault.NAME)).isEqualTo(StrataDefault.document)
        for (p in listOf(light, dark, PlatformInputs(26), PlatformInputs(31, true, WallpaperColorsInput(0xFF336699.toInt())))) {
            assertThat(withoutId(resolve(parsed.document, p))).isEqualTo(withoutId(resolve(StrataDefault.document, p)))
        }
        assertThat(withoutId(StrataDefault.resolved(false))).isEqualTo(withoutId(resolve(parsed.document, PlatformInputs(26))))
        assertThat(StrataDefault.resolved(true).colors).isEqualTo(BuiltInPalettes.DARK)
        assertThat(StrataDefault.resolved(false).colors).isEqualTo(BuiltInPalettes.LIGHT)
        assertThat(StrataDefault.resolved(false).contrast.status).isEqualTo(ContrastStatus.AA_ANY_WALLPAPER)
        assertThat(StrataDefault.resolved(false).contrast.enforced).isFalse()
    }

    @Test
    fun `T-THEME-04 an omitted role takes the built-in value of its variant, a set role is used`() {
        for (variant in Variant.entries) {
            val base = BuiltInPalettes.of(variant)
            for (omitted in ColorRole.entries) {
                val roles = ColorRole.entries.filter { it != omitted }
                val pal = roles.joinToString(",") { "\"${it.wire}\":\"#FF00FF\"" }
                val doc = parseOk(minimal("\"colors\":{\"mode\":\"${variant.wire}\",\"${variant.wire}\":{$pal}}")).document
                // Enforcement off: this probe checks role plumbing, not readability.
                val t = resolve(doc, light, ThemeGlobals(enforceContrast = false))
                assertThat(t.variant).isEqualTo(variant)
                for (r in ColorRole.entries) {
                    val expected = if (r == omitted) base[r] else magenta
                    assertWithMessage("$variant omit $omitted role $r").that(t.colors[r]).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun `T-THEME-06 pure black blackens background and scrim in dark only`() {
        val doc = parseOk(minimal("\"colors\":{\"pureBlack\":true}")).document
        val d = resolve(doc, dark)
        assertThat(d.colors.background).isEqualTo(Rgb.BLACK)
        assertThat(d.colors.scrim).isEqualTo(Rgb.BLACK)
        assertThat(d.colors.copy(background = BuiltInPalettes.DARK.background, scrim = BuiltInPalettes.DARK.scrim)).isEqualTo(BuiltInPalettes.DARK)
        val l = resolve(doc, light)
        assertThat(l.colors).isEqualTo(BuiltInPalettes.LIGHT)
        // A derived dark palette is blackened too (applied after derivation).
        val seeded = doc.copy(colors = doc.colors.copy(sourceKind = SourceKind.SEED, seedColor = 0xFF1E88E5.toInt()))
        assertThat(resolve(seeded, dark).colors.background).isEqualTo(Rgb.BLACK)
    }

    private val wallpaperA = WallpaperColorsInput(0xFF2E7D32.toInt(), 0xFF81C784.toInt(), 0xFF1B5E20.toInt())
    private val wallpaperB = WallpaperColorsInput(0xFFC62828.toInt(), 0xFFFFCDD2.toInt(), 0xFF4A148C.toInt(), supportsDarkText = true)
    private val dynamic =
        DynamicSchemeInput(
            light = MaterialRoles(0xFFFEF7FF.toInt(), 0xFFECE6F0.toInt(), 0xFFE6E0E9.toInt(), 0xFF1D1B20.toInt(), 0xFF49454F.toInt(), 0xFF65558F.toInt(), 0xFFFFFFFF.toInt(), 0xFFCAC4D0.toInt()),
            dark = MaterialRoles(0xFF141218.toInt(), 0xFF2B2930.toInt(), 0xFF36343B.toInt(), 0xFFE6E0E9.toInt(), 0xFFCAC4D0.toInt(), 0xFFD0BCFE.toInt(), 0xFF381E72.toInt(), 0xFF49454F.toInt()),
        )

    private fun assertAA(
        t: ResolvedTheme,
        doc: ThemeDocument,
        wc: WallpaperColorsInput?,
        what: String,
    ) {
        val w = WallpaperSpec(t.wallpaper.show, t.wallpaper.dim, t.wallpaper.backgroundOpacity)
        val ok = Contrast.passes(t.colors, w, wc?.all)
        assertWithMessage("$what: ${Contrast.check(t.colors, w, wc?.all)}").that(ok).isTrue()
    }

    @Test
    fun `T-THEME-07 dynamic uses the dynamic scheme on 31+, wallpaper colours on 27-30, fixed on 26`() {
        val harbor = sample("harbor").document
        for (isDark in listOf(false, true)) {
            val api33 = resolve(harbor, PlatformInputs(33, isDark, wallpaperA, dynamic))
            assertThat(api33.colorSource).isEqualTo(SourceKind.DYNAMIC)
            val scheme = if (isDark) dynamic.dark else dynamic.light
            assertThat(ThemeResolver.map(scheme, isDark).background).isEqualTo(api33.colors.background)
            assertAA(api33, harbor, wallpaperA, "api33")

            val api30 = resolve(harbor, PlatformInputs(30, isDark, wallpaperA, dynamic))
            assertThat(api30.colorSource).isEqualTo(SourceKind.WALLPAPER)
            assertThat(api30.colors).isNotEqualTo(harbor.colors.palette(api30.variant))
            assertAA(api30, harbor, wallpaperA, "api30")

            val api26 = resolve(harbor, PlatformInputs(26, isDark, wallpaperA, dynamic))
            assertThat(api26.colorSource).isEqualTo(SourceKind.FIXED)
            assertThat(api26.colors).isEqualTo(harbor.colors.palette(api26.variant))
        }
        // No wallpaper colours (some live wallpapers): dynamic → wallpaper → fixed.
        assertThat(resolve(harbor, PlatformInputs(30, false, null, null)).colorSource).isEqualTo(SourceKind.FIXED)
    }

    @Test
    fun `T-THEME-07 seed palettes are derived and enforced, invalid seed falls back to fixed`() {
        val base = parseOk(minimal()).document
        for (seed in listOf(0xFF1E88E5.toInt(), 0xFFFFEB3B.toInt(), 0xFF000000.toInt(), 0xFF7F7F7F.toInt())) {
            val doc = base.copy(colors = ColorsSpec(sourceKind = SourceKind.SEED, seedColor = seed))
            for (p in listOf(light, dark)) {
                val t = resolve(doc, p, ThemeGlobals(enforceContrast = false)) // derived palettes are enforced regardless
                assertThat(t.colorSource).isEqualTo(SourceKind.SEED)
                assertAA(t, doc, null, "seed ${Rgb.toHex(seed)}")
            }
        }
        val noSeed = parseOk(minimal("\"colors\":{\"source\":{\"kind\":\"seed\"}}"))
        assertThat(noSeed.issues.map { it.code }).contains(app.strata.launcher.core.theme.parse.IssueCode.SEED_MISSING)
        assertThat(resolve(noSeed.document, light).colorSource).isEqualTo(SourceKind.FIXED)
    }

    @Test
    fun `T-THEME-07 locked roles never change when the wallpaper changes`() {
        val dusk = sample("dusk").document // wallpaper source, scrim locked
        for (api in listOf(27, 30, 31, 34)) {
            val a = resolve(dusk, PlatformInputs(api, false, wallpaperA))
            val b = resolve(dusk, PlatformInputs(api, false, wallpaperB))
            assertThat(a.colorSource).isEqualTo(SourceKind.WALLPAPER)
            assertThat(a.colors.scrim).isEqualTo(dusk.colors.palette(a.variant).scrim)
            assertThat(b.colors.scrim).isEqualTo(dusk.colors.palette(b.variant).scrim)
            assertThat(a.colors.background).isNotEqualTo(b.colors.background)
            assertAA(a, dusk, wallpaperA, "dusk A api $api")
            assertAA(b, dusk, wallpaperB, "dusk B api $api")
        }
        // Several locked roles on a seed theme.
        val locked = setOf(ColorRole.ACCENT, ColorRole.SURFACE)
        val doc = parseOk(minimal()).document.let { it.copy(colors = ColorsSpec(sourceKind = SourceKind.WALLPAPER, lockedRoles = locked)) }
        for (wc in listOf(wallpaperA, wallpaperB)) {
            val t = resolve(doc, PlatformInputs(30, false, wc))
            assertThat(t.colors.accent).isEqualTo(BuiltInPalettes.LIGHT.accent)
            assertThat(t.colors.surface).isEqualTo(BuiltInPalettes.LIGHT.surface)
        }
    }

    @Test
    fun `wallpaper mode picks the variant per API level`() {
        val dusk = sample("dusk").document
        assertThat(resolve(dusk, PlatformInputs(31, systemDark = true, wallpaperColors = wallpaperB)).variant).isEqualTo(Variant.LIGHT)
        assertThat(resolve(dusk, PlatformInputs(31, systemDark = false, wallpaperColors = wallpaperA)).variant).isEqualTo(Variant.DARK)
        assertThat(resolve(dusk, PlatformInputs(29, systemDark = true, wallpaperColors = WallpaperColorsInput(Rgb.WHITE))).variant).isEqualTo(Variant.LIGHT)
        assertThat(resolve(dusk, PlatformInputs(29, systemDark = false, wallpaperColors = WallpaperColorsInput(Rgb.BLACK))).variant).isEqualTo(Variant.DARK)
        assertThat(resolve(dusk, PlatformInputs(26, systemDark = true, wallpaperColors = wallpaperB)).variant).isEqualTo(Variant.DARK)
        assertThat(resolve(dusk, PlatformInputs(33, systemDark = true, wallpaperColors = null)).variant).isEqualTo(Variant.DARK)
        // Global override wins over the theme.
        assertThat(resolve(dusk, PlatformInputs(31, false, wallpaperA), ThemeGlobals(AppearanceOverride.LIGHT)).variant).isEqualTo(Variant.LIGHT)
        assertThat(resolve(sample("paper").document, dark, ThemeGlobals(AppearanceOverride.SYSTEM)).variant).isEqualTo(Variant.DARK)
    }

    private val failing =
        BuiltInPalettes.LIGHT.copy(
            textPrimary = 0xFFBBBBBB.toInt(),
            textSecondary = 0xFFDDDDDD.toInt(),
            accent = 0xFFEEEEEE.toInt(),
            onAccent = 0xFFF0F0F0.toInt(),
            highlight = 0xFFC0C0C0.toInt(),
        )

    @Test
    fun `T-THEME-25 enforcement makes a failing theme pass P1-P8 (static and runtime) and leaves the file unchanged`() {
        val shownWallpapers = listOf(WallpaperSpec(), WallpaperSpec(show = true, dim = 0.1, backgroundOpacity = 0.0), WallpaperSpec(show = true, dim = 0.5, backgroundOpacity = 0.3))
        for (w in shownWallpapers) {
            val doc = parseOk(minimal()).document.copy(colors = ColorsSpec(light = failing), wallpaper = w)
            val bytes = ThemeWriter.write(app.strata.launcher.core.theme.write.ThemeEncoder.encode(doc))
            val before = bytes.copyOf()
            val parsed = parseOk(bytes).document
            assertThat(Contrast.passes(failing, w)).isFalse()
            // Static (no wallpaper colours).
            val s = ThemeResolver.resolve(parsed, light, ThemeGlobals()).theme
            assertThat(s.contrast.enforced).isTrue()
            assertThat(s.contrast.status).isEqualTo(ContrastStatus.AA_ANY_WALLPAPER)
            assertAA(s, parsed, null, "static $w")
            // Runtime (wallpaper colours exist).
            for (wc in listOf(wallpaperA, wallpaperB)) {
                val r = ThemeResolver.resolve(parsed, PlatformInputs(34, false, wc), ThemeGlobals()).theme
                assertAA(r, parsed, wc, "runtime $w $wc")
            }
            assertThat(bytes).isEqualTo(before)
            // Hue and chroma are kept by tone moves: grey text stays (near) neutral.
            assertThat(kotlin.math.abs(Rgb.red(s.colors.textPrimary) - Rgb.blue(s.colors.textPrimary))).isAtMost(4)
        }
        // Enforcement off: colours are rendered as written, with a low-contrast warning.
        val doc = parseOk(minimal()).document.copy(colors = ColorsSpec(light = failing))
        val off = ThemeResolver.resolve(doc, light, ThemeGlobals(enforceContrast = false))
        assertThat(off.theme.colors).isEqualTo(failing)
        assertThat(off.theme.contrast.status).isEqualTo(ContrastStatus.LOW_CONTRAST)
        assertThat(off.issues.map { it.code }).contains(app.strata.launcher.core.theme.parse.IssueCode.LOW_CONTRAST)
    }

    @Test
    fun `T-THEME-25 enforcement raises dim then background opacity when tones are not enough`() {
        // Mid-grey text over a fully transparent backdrop and a mid-grey scrim cannot pass by tone alone
        // for every wallpaper: dimming must rise.
        val p = BuiltInPalettes.DARK.copy(scrim = 0xFF000000.toInt(), background = 0xFF000000.toInt())
        val w = WallpaperSpec(show = true, dim = 0.0, backgroundOpacity = 0.0)
        val out = app.strata.launcher.core.theme.contrast.ContrastEnforcer.enforce(p, w, null, setOf(ColorRole.BACKGROUND, ColorRole.SCRIM))
        assertThat(out.wallpaper.dim).isGreaterThan(0.0)
        assertThat(out.wallpaper.dim).isAtMost(0.95)
        assertThat(Contrast.passes(out.palette, out.wallpaper)).isTrue()
    }

    @Test
    fun `global settings and forced-on rules`() {
        val doc = parseOk(minimal("\"icons\":{\"visible\":false,\"labelsVisible\":false},\"favorites\":{\"showIcons\":false,\"showLabels\":false},\"haptics\":{\"mode\":\"followSystem\"},\"animation\":{\"durationScale\":1.5,\"listEntrance\":\"slide\"}")).document
        val t = resolve(doc, light.copy(animatorDurationScale = 0.5))
        assertThat(t.icons.labelsVisible).isTrue()
        assertThat(t.favorites.showLabels).isTrue()
        assertThat(t.animation.durationScale).isWithin(1e-9).of(0.75)
        assertThat(t.haptics.enabled).isTrue()
        assertThat(resolve(doc, light, ThemeGlobals(hapticsEnabled = false)).haptics.enabled).isFalse()
        val off = resolve(doc, light.copy(animatorsEnabled = false))
        assertThat(off.animation.durationScale).isEqualTo(0.0)
        assertThat(off.animation.listEntrance).isEqualTo(app.strata.launcher.core.theme.model.ListEntrance.NONE)
        assertThat(resolve(doc, light.copy(animatorDurationScale = 0.0)).animation.enabled).isFalse()
    }

    @Test
    fun `missing references fall back with warnings`() {
        val sha = "a".repeat(64)
        val doc =
            parseOk(
                minimal(
                    "\"typography\":{\"family\":{\"kind\":\"user\",\"sha256\":\"$sha\"},\"clockFamily\":{\"kind\":\"bundled\",\"id\":\"nope\"}}," +
                        "\"icons\":{\"pack\":{\"package\":\"com.example.pack\",\"displayName\":\"Pack\",\"fallback\":\"monochrome\"}}," +
                        "\"wallpaper\":{\"image\":{\"sha256\":\"$sha\"}}",
                ),
            ).document
        val r = ThemeResolver.resolve(doc, light)
        assertThat(r.issues.map { it.code }).containsAtLeast(
            app.strata.launcher.core.theme.parse.IssueCode.FONT_MISSING,
            app.strata.launcher.core.theme.parse.IssueCode.BUNDLED_FONT_UNKNOWN,
            app.strata.launcher.core.theme.parse.IssueCode.ICON_PACK_MISSING,
            app.strata.launcher.core.theme.parse.IssueCode.IMAGE_MISSING,
        )
        assertThat(r.theme.typography.family).isEqualTo(app.strata.launcher.core.theme.resolve.ResolvedFont.System)
        assertThat(r.theme.icons.packPackage).isNull()
        assertThat(r.theme.icons.missingPackPackage).isEqualTo("com.example.pack")
        val present = ThemeResolver.resolve(doc, light.copy(userFonts = setOf(sha), installedIconPacks = setOf("com.example.pack"), images = setOf(sha), bundledFonts = setOf("nope")))
        assertThat(present.issues).isEmpty()
        assertThat(present.theme.icons.packPackage).isEqualTo("com.example.pack")
        assertThat(present.theme.wallpaper.imageSha256).isEqualTo(sha)
    }

    @Test
    fun `T-THEME-49 resolve is deterministic and matches the committed snapshot`() {
        val inputs =
            listOf(
                "harbor-api33-dark" to (sample("harbor").document to PlatformInputs(33, true, wallpaperA, dynamic)),
                "dusk-api30" to (sample("dusk").document to PlatformInputs(30, false, wallpaperB)),
                "graphite-api34" to (sample("graphite").document to PlatformInputs(34, true, wallpaperA)),
                "seed-light" to (parseOk(minimal()).document.copy(colors = ColorsSpec(sourceKind = SourceKind.SEED, seedColor = 0xFF8E24AA.toInt())) to light),
            )
        val snapshot = StringBuilder()
        for ((name, pair) in inputs) {
            val (doc, p) = pair
            val first = resolve(doc, p)
            repeat(100) { assertThat(resolve(doc, p)).isEqualTo(first) }
            snapshot.append(name).append(": ").append(describe(first)).append('\n')
        }
        // The snapshot file is the cross-process half of T-THEME-49: every test JVM must produce it again.
        val expected = javaClass.classLoader.getResourceAsStream("golden/resolve-snapshot.txt")?.bufferedReader()?.readText()
        if (expected == null || System.getProperty("strata.updateGolden") == "true") {
            java.io.File("src/test/resources/golden/resolve-snapshot.txt").writeText(snapshot.toString())
            if (expected == null) error("golden/resolve-snapshot.txt was missing; written, re-run the test")
        } else {
            assertThat(snapshot.toString()).isEqualTo(expected)
        }
    }

    private fun describe(t: ResolvedTheme): String {
        val c = t.colors
        val colors = ColorRole.entries.joinToString(" ") { "${it.wire}=${Rgb.toHex(c[it])}" }
        return "${t.variant} ${t.colorSource} $colors dim=${t.wallpaper.dim} bgOpacity=${t.wallpaper.backgroundOpacity} " +
            "contrast=${t.contrast} anim=${t.animation}"
    }

    @Test
    fun `resolveSafely falls back to Strata Default instead of throwing`() {
        val doc = parseOk(minimal("\"icons\":{\"pack\":{\"package\":\"com.example.pack\"}}")).document
        val exploding =
            object : AbstractSet<String>() {
                override val size: Int get() = throw IllegalStateException("boom")

                override fun iterator(): Iterator<String> = throw IllegalStateException("boom")

                override fun contains(element: String): Boolean = throw IllegalStateException("boom")
            }
        val errors = ArrayList<Throwable>()
        val r = ThemeResolver.resolveSafely(doc, light.copy(installedIconPacks = exploding), onError = { errors += it })
        assertThat(r.fellBack).isTrue()
        assertThat(r.theme.themeId).isEqualTo(StrataDefault.ID)
        assertThat(errors).hasSize(1)
        assertThat(ThemeResolver.resolveSafely(doc, light).fellBack).isFalse()
    }
}
