package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.color.Hct
import app.strata.launcher.core.theme.contrast.Contrast
import app.strata.launcher.core.theme.contrast.ContrastPair
import app.strata.launcher.core.theme.editor.EditorContrast
import app.strata.launcher.core.theme.editor.EditorDraft
import app.strata.launcher.core.theme.editor.EditorFields
import app.strata.launcher.core.theme.editor.EditorSession
import app.strata.launcher.core.theme.editor.FieldPath
import app.strata.launcher.core.theme.editor.ImageTheme
import app.strata.launcher.core.theme.editor.JsonApply
import app.strata.launcher.core.theme.editor.SeedExtractor
import app.strata.launcher.core.theme.editor.TreeEdits
import app.strata.launcher.core.theme.editor.UndoHistory
import app.strata.launcher.core.theme.model.ColorRole
import app.strata.launcher.core.theme.model.SourceKind
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.model.WallpaperImage
import app.strata.launcher.core.theme.model.WallpaperSpec
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeResolver
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.InMemoryThemeFileStore
import app.strata.launcher.testing.fakes.InMemoryThemeIndexStore
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The editor core (THEME_SYSTEM §13, §7.5, §7.6): T-THEME-37 (U half: ≥ 100 undo steps, coalesced drags, resets as
 * one step each), T-THEME-24 (U half: banner pairs, Fix in one step, undo), T-THEME-38 (U half: seed palette passes
 * P1–P8), and the draft format.
 */
class EditorSessionTest {
    private val inputs = PlatformInputs(apiLevel = 34)

    private fun session(extra: String = ""): EditorSession {
        val t = json(minimal(extra))
        return EditorSession(MIN_ID, MIN_ID, t, saved = t)
    }

    private val accent = EditorFields.colour(Variant.LIGHT, ColorRole.ACCENT)
    private val scale = EditorFields.field("typography.scale")!!
    private val iconSize = EditorFields.field("icons.sizeDp")!!

    // ---- T-THEME-37 ---------------------------------------------------------------------------------

    @Test
    fun tTheme37_120EditsKeepAtLeast100UndoSteps() {
        val s = session()
        repeat(120) { i -> assertThat(s.set(iconSize, if (i % 2 == 0) 40 else 41)).isTrue() }
        assertThat(s.undoDepth).isAtLeast(100)
        var undone = 0
        while (s.undo()) undone++
        assertThat(undone).isAtLeast(100)
        assertThat(s.canRedo).isTrue()
    }

    @Test
    fun tTheme37_aDragIsOneStep() {
        val s = session()
        s.set(iconSize, 40) // an earlier, separate step
        for (v in 0..300) s.set(scale, 0.8 + v * 0.7 / 300.0, coalesce = true)
        s.endCoalescing()
        assertThat(s.undoDepth).isEqualTo(2)
        assertThat(s.document.typography.scale).isEqualTo(1.5)
        assertThat(s.undo()).isTrue()
        assertThat(s.document.typography.scale).isEqualTo(1.0)
        assertThat(s.document.icons.sizeDp).isEqualTo(40)
        // A second drag of the same slider is a new step.
        for (v in 0..10) s.set(scale, 1.0 + v * 0.01, coalesce = true)
        s.endCoalescing()
        for (v in 0..10) s.set(scale, 1.2 + v * 0.01, coalesce = true)
        s.endCoalescing()
        assertThat(s.undoDepth).isEqualTo(3)
    }

    @Test
    fun dragStartingAtTheCurrentValueDoesNotRewriteTheEarlierStep() {
        val s = session()
        s.set(iconSize, 40)
        s.set(scale, 1.0, coalesce = true) // equal to the current value: no step, no open run
        s.set(scale, 1.2, coalesce = true)
        s.endCoalescing()
        assertThat(s.undoDepth).isEqualTo(2)
        s.undo()
        assertThat(s.document.icons.sizeDp).isEqualTo(40)
    }

    @Test
    fun historyCapacityHasTheSpecMinimum() {
        val h = UndoHistory(0, capacity = 100)
        repeat(150) { h.record(it + 1) }
        assertThat(h.undoDepth).isEqualTo(100)
        assertThat(UndoHistory.DEFAULT_CAPACITY).isAtLeast(100)
    }

    @Test
    fun valuesAreNormalisedToTheSchemaRange() {
        val s = session()
        s.set(iconSize, 500)
        assertThat(s.document.icons.sizeDp).isEqualTo(64)
        s.set(scale, 1.2345)
        assertThat((TreeEdits.get(s.tree, scale.path) as JsonNumber).toDouble()).isEqualTo(1.23)
        assertThat(s.set(accent, "not a colour")).isFalse()
        s.set(accent, "#ff0000")
        assertThat(TreeEdits.get(s.tree, accent.path)).isEqualTo(JsonString("#FF0000"))
    }

    @Test
    fun resetsAreOneStepEachAndKeepUnknowns() {
        val s =
            session(""""x-keep":1,"icons":{"sizeDp":40,"shape":"circle","x-own":true},"list":{"density":"compact"}""")
        s.set(accent, "#123456")
        val before = s.undoDepth

        assertThat(s.reset(EditorFields.field("icons.sizeDp")!!)).isTrue()
        assertThat(s.document.icons.sizeDp).isEqualTo(32)
        assertThat(s.document.icons.shape.wire).isEqualTo("circle")

        assertThat(s.resetGroup(EditorFields.group("icons")!!)).isTrue()
        assertThat(s.tree["icons"]).isEqualTo(JsonObject("x-own" to app.strata.launcher.core.json.JsonBool.TRUE))

        assertThat(s.resetTheme(null)).isTrue()
        assertThat(s.tree["list"]).isNull()
        assertThat(s.tree["colors"]).isNull()
        assertThat(s.document.id).isEqualTo(MIN_ID)
        assertThat(s.document.name).isEqualTo("T")
        assertThat(s.undoDepth).isEqualTo(before + 3)
        s.undo()
        assertThat(s.tree["list"]).isNotNull()
    }

    @Test
    fun resetToPresetTakesThePresetsLook() {
        val s = session()
        val dusk = sample("dusk")
        s.resetTheme(dusk.tree)
        assertThat(s.document.colors).isEqualTo(dusk.document.colors)
        assertThat(s.document.id).isEqualTo(MIN_ID)
        assertThat(s.document.name).isEqualTo("T")
    }

    @Test
    fun jsonViewAppliesValidTextAsOneStepAndKeepsTheId() {
        val s = session()
        val other = "99999999-0000-4000-8000-000000000009"
        val r = s.applyJson("""{"schemaVersion":1,"id":"$other","name":"Renamed","list":{"density":"spacious"}}""")
        assertThat(r).isInstanceOf(JsonApply.Applied::class.java)
        assertThat((r as JsonApply.Applied).idKept).isTrue()
        assertThat(s.document.id).isEqualTo(MIN_ID)
        assertThat(s.document.name).isEqualTo("Renamed")
        assertThat(s.undoDepth).isEqualTo(1)

        val bad = s.applyJson("""{"schemaVersion":1, "id": }""")
        assertThat(bad).isInstanceOf(JsonApply.Rejected::class.java)
        assertThat((bad as JsonApply.Rejected).error).isInstanceOf(ThemeError.Damaged::class.java)
        val newer = s.applyJson("""{"schemaVersion":2,"id":"$MIN_ID","name":"T"}""")
        assertThat((newer as JsonApply.Rejected).error).isInstanceOf(ThemeError.NewerVersion::class.java)
        assertThat(s.undoDepth).isEqualTo(1)
    }

    @Test
    fun dirtyComparesWithTheSavedVersion() {
        val s = session()
        assertThat(s.dirty).isFalse()
        s.set(iconSize, 40)
        assertThat(s.dirty).isTrue()
        s.undo()
        assertThat(s.dirty).isFalse()
        s.set(iconSize, 40)
        s.markSaved(s.tree.with("modifiedAt", JsonString("2027-01-01T00:00:00Z")))
        assertThat(s.dirty).isFalse()
    }

    // ---- T-THEME-24 ---------------------------------------------------------------------------------

    private val p2p5 = """"colors":{"mode":"light","light":{"textSecondary":"#BBBBBB"}}"""

    @Test
    fun tTheme24_bannerListsP2AndP5AndFixPassesInOneStep() {
        val s = session(p2p5)
        val report = EditorContrast.report(s.document, inputs)
        assertThat(report.failing.map { it.pair }).containsExactly(ContrastPair.P2, ContrastPair.P5).inOrder()
        val chip = report.chip(Variant.LIGHT, ColorRole.TEXT_SECONDARY)!!
        assertThat(chip.passes).isFalse()
        assertThat(report.chip(Variant.LIGHT, ColorRole.DIVIDER)).isNull()

        val fixed = EditorContrast.fix(s.tree, { (parser.validate(it) as? Outcome.Ok)?.value?.document }, inputs)
        assertThat(s.replace(fixed)).isTrue()
        assertThat(s.undoDepth).isEqualTo(1)
        assertThat(EditorContrast.report(s.document, inputs).allPass).isTrue()
        assertThat(Contrast.staticCheck(s.document).all { it.passes }).isTrue()
        // Only the failing role moved.
        assertThat(
            s.document.colors.light
                .copy(textSecondary = 0),
        ).isEqualTo(
            parseOk(minimal(p2p5))
                .document.colors.light
                .copy(textSecondary = 0),
        )

        s.undo()
        assertThat(EditorContrast.report(s.document, inputs).failing.map { it.pair })
            .containsExactly(ContrastPair.P2, ContrastPair.P5)
    }

    @Test
    fun fixCoversBothVariantsPureBlackAndTheWallpaper() {
        for (extra in listOf(
            """"colors":{"light":{"accent":"#DDDDDD"},"dark":{"textPrimary":"#333333"}}""",
            """"wallpaper":{"show":true}""",
        )) {
            val s = session(extra)
            assertThat(EditorContrast.report(s.document, inputs).allPass).isFalse()
            val fixed = EditorContrast.fix(s.tree, { (parser.validate(it) as? Outcome.Ok)?.value?.document }, inputs)
            s.replace(fixed)
            assertThat(EditorContrast.report(s.document, inputs).allPass).isTrue()
            assertThat(Contrast.staticCheck(s.document).all { it.passes }).isTrue()
            assertThat(s.undoDepth).isEqualTo(1)
        }
    }

    @Test
    fun failingKeyChangesOnlyWhenTheProblemsChange() {
        val a = EditorContrast.report(parseOk(minimal(p2p5)).document, inputs)
        val b =
            EditorContrast.report(
                parseOk(minimal(""""colors":{"mode":"light","light":{"textSecondary":"#C0C0C0"}}""")).document,
                inputs,
            )
        assertThat(a.failingKey).isEqualTo(b.failingKey)
        assertThat(EditorContrast.report(StrataDefault.document, inputs).failingKey).isEmpty()
    }

    // ---- T-THEME-38 ---------------------------------------------------------------------------------

    @Test
    fun tTheme38_seedFromImageGivesAPassingSeedPalette() {
        val orange = 0xFFE07020.toInt()
        val pixels = IntArray(4096) { i -> if (i % 10 < 7) orange else 0xFF808080.toInt() }
        val seed = SeedExtractor.seed(pixels)!!
        assertThat(Hct.fromArgb(seed).hue).isWithin(15.0).of(Hct.fromArgb(orange).hue)

        val s = session()
        val image = WallpaperImage("a".repeat(64))
        s.replace(ImageTheme.apply(s.tree, seed, s.document.wallpaper, image))
        val doc = s.document
        assertThat(doc.colors.sourceKind).isEqualTo(SourceKind.SEED)
        assertThat(doc.colors.seedColor).isEqualTo(seed)
        assertThat(doc.wallpaper.image?.sha256).isEqualTo("a".repeat(64))
        // The fixed fallback palettes pass on any wallpaper, pure black included …
        assertThat(Contrast.staticCheck(doc).all { it.passes }).isTrue()
        // … and so does the rendered seed palette (P1–P8), light and dark.
        for (dark in listOf(false, true)) {
            val r = ThemeResolver.resolve(doc, inputs.copy(systemDark = dark)).theme
            assertThat(r.colorSource).isEqualTo(SourceKind.SEED)
            assertThat(Contrast.passes(r.colors, WallpaperSpec())).isTrue()
        }
    }

    @Test
    fun seedOfGreysAndOfNothing() {
        assertThat(SeedExtractor.seed(IntArray(0))).isNull()
        assertThat(SeedExtractor.seed(IntArray(10) { 0x00FF0000 })).isNull()
        val grey = 0xFF777777.toInt()
        assertThat(SeedExtractor.seed(IntArray(100) { if (it < 60) grey else 0xFFEEEEEE.toInt() })).isEqualTo(grey)
    }

    // ---- drafts ---------------------------------------------------------------------------------------

    @Test
    fun draftRoundTripAndGarbage() {
        val d = EditorDraft(MIN_ID, null, isNew = true, savedAtMillis = 42, tree = json(minimal(p2p5)))
        assertThat(EditorDraft.decode(EditorDraft.encode(d))).isEqualTo(d)
        assertThat(EditorDraft.decode("{}".toByteArray())).isNull()
        assertThat(EditorDraft.decode(byteArrayOf(1, 2, 3))).isNull()
    }

    @Test
    fun libraryDraftsAndTheirImagesSurviveGarbageCollection() {
        val files = InMemoryThemeFileStore()
        val lib =
            ThemeLibrary(files, InMemoryThemeIndexStore(), MapBuiltInThemeSource(ThemeFixtures.samples()), FakeClock(1))
        lib.boot()
        val sha = "b".repeat(64)
        files.write("images/$sha.webp", byteArrayOf(1))
        val tree =
            TreeEdits.set(
                json(minimal()),
                FieldPath("wallpaper", "image"),
                JsonObject("sha256" to JsonString(sha), "mediaType" to JsonString("image/webp")),
            )
        lib.writeDraft(EditorDraft(MIN_ID, null, isNew = true, savedAtMillis = 5, tree = tree))
        lib.collectGarbage()
        assertThat(files.list("images")).contains("$sha.webp")
        assertThat(lib.drafts().map { it.sessionId }).containsExactly(MIN_ID)
        assertThat(lib.readDraft(MIN_ID)?.tree).isEqualTo(tree)

        files.write("drafts/${"c".repeat(8)}-0000-4000-8000-000000000000.json", "garbage".toByteArray())
        assertThat(lib.drafts()).hasSize(1)
        assertThat(files.list("drafts")).hasSize(1)

        lib.deleteDraft(MIN_ID)
        assertThat(lib.drafts()).isEmpty()
        lib.collectGarbage()
        assertThat(files.list("images")).doesNotContain("$sha.webp")
    }

    @Test
    fun copyTreeIsNotWritten() {
        val lib =
            ThemeLibrary(
                InMemoryThemeFileStore(),
                InMemoryThemeIndexStore(),
                MapBuiltInThemeSource(ThemeFixtures.samples()),
                FakeClock(1),
            )
        lib.boot()
        val before = lib.summaries()
        val copy = lib.copyTree(StrataDefault.ID, MIN_ID)!!
        assertThat((copy["name"] as JsonString).value).isEqualTo("Strata Default (copy)")
        assertThat((copy["basedOn"] as JsonString).value).isEqualTo(StrataDefault.ID)
        assertThat(lib.summaries()).isEqualTo(before)
    }
}
