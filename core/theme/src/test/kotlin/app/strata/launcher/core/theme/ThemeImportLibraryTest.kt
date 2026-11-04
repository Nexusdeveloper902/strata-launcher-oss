package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.importing.BundleCodec
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImportCandidate
import app.strata.launcher.core.theme.importing.ImportConflict
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ImportMessage
import app.strata.launcher.core.theme.importing.ThemeImporter
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.store.BundleExport
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeOrigin
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.InMemoryThemeFileStore
import app.strata.launcher.testing.fakes.InMemoryThemeIndexStore
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fixtures.BundleFixtures
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * T-THEME-01 (unlimited, ungated), T-THEME-31/32 (version gate), T-THEME-34 (U half: the four conflict rows),
 * T-THEME-35 (R half: library operations) and the library side of T-THEME-28 (store, export, re-import).
 */
class ThemeImportLibraryTest {
    private val files = InMemoryThemeFileStore()
    private val indexStore = InMemoryThemeIndexStore()
    private val clock = FakeClock(1_800_000_000_000)
    private val ids = fixedIds()
    private val importer = ThemeImporter(parser, ThemeMedia.TRUSTING, ids)
    private val library =
        ThemeLibrary(files, indexStore, MapBuiltInThemeSource(ThemeFixtures.samples()), clock).also { it.boot() }

    private val paperId = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"

    private fun theme(
        id: String,
        name: String,
        accent: String = "#2F5D8A",
        extra: String = "",
    ): ByteArray =
        """{"schemaVersion":1,"id":"$id","name":"$name","colors":{"light":{"accent":"$accent"}}$extra}""".toByteArray()

    private fun candidate(bytes: ByteArray): ImportCandidate = (importer.prepare(bytes) as Outcome.Ok).value

    /** Prepare + plan + commit, as the repository does. Returns the saved id, or null for IDENTICAL. */
    private fun import(
        bytes: ByteArray,
        choice: ConflictChoice = ConflictChoice.KEEP_BOTH,
    ): Pair<ImportConflict, String?> {
        val c = candidate(bytes)
        val plan = importer.plan(c, library.snapshot())
        if (plan.conflict == ImportConflict.IDENTICAL) return plan.conflict to null
        val (saved, _) = library.commitImport(c, importer.treeFor(c, plan, choice))
        return plan.conflict to saved.document.id
    }

    private fun name(id: String) = library.summaries().first { it.id.equals(id, ignoreCase = true) }.name

    // ---- T-THEME-01 ---------------------------------------------------------------------------------

    @Test
    fun tTheme01_unlimitedThemesNoGate() {
        repeat(500) { i ->
            library.save(json(String(theme("%08d-0000-4000-8000-000000000000".format(i), "Theme $i"))))
        }
        repeat(50) { i -> import(theme("%08d-1111-4000-8000-000000000000".format(i), "Imported $i")) }
        val all = library.summaries()
        assertThat(all.count { !it.builtIn }).isEqualTo(550)
        val rnd = Random(1)
        repeat(20) {
            val t = all[rnd.nextInt(all.size)]
            library.apply(t.id)
            assertThat(library.loadTheme(t.id)).isInstanceOf(Outcome.Ok::class.java)
            assertThat(library.currentIndex.activeThemeId).isEqualTo(t.id)
        }
    }

    // ---- T-THEME-31 / T-THEME-32 --------------------------------------------------------------------

    @Test
    fun tTheme31_newerMajorIsRejectedAndNothingIsWritten() {
        val before = files.files.keys.toList()
        val r = importer.prepare("""{"schemaVersion":2,"id":"$MIN_ID","name":"Future"}""".toByteArray())
        assertThat((r as Outcome.Err).error.message).isEqualTo(ImportMessage.NEWER_VERSION)
        assertThat((r.error as ImportError.Theme).detail).startsWith("This theme was made with a newer version")
        assertThat(files.files.keys.toList()).isEqualTo(before)
    }

    @Test
    fun tTheme32_newerMinorImportsKeepsUnknownsAndRoundTrips() {
        val bytes =
            """{"schemaVersion":1,"schemaMinor":5,"id":"$MIN_ID","name":"Next","futureGroup":{"a":1},
               "colors":{"mode":"sepia","futureRole":"#123456"},"x-tool":{"k":true}}""".toByteArray()
        val c = candidate(bytes)
        // futureGroup, the colors.mode value and colors.futureRole; x-tool is an extension, not a setting.
        assertThat(ThemeImporter.newerSettings(c.parsed)).isEqualTo(3)
        val (_, id) = import(bytes)
        val exported = library.exportJson(id!!)!!
        val back = parseOk(exported)
        assertThat(back.document.schemaMinor).isEqualTo(5) // max(file, app)
        assertThat(back.tree["futureGroup"]).isEqualTo(c.parsed.tree["futureGroup"])
        assertThat(back.tree["x-tool"]).isEqualTo(c.parsed.tree["x-tool"])
        assertThat(
            (back.tree["colors"] as app.strata.launcher.core.json.JsonObject)["mode"],
        ).isEqualTo(JsonString("sepia"))
    }

    // ---- T-THEME-34 ---------------------------------------------------------------------------------

    @Test
    fun tTheme34_identicalIsAlreadyInYourThemes() {
        val (_, id) = import(theme(MIN_ID, "Mine"))
        val countBefore = library.summaries().size
        // An exported copy has a refreshed modifiedAt: still identical.
        val (conflict, saved) = import(library.exportJson(id!!)!!)
        assertThat(conflict).isEqualTo(ImportConflict.IDENTICAL)
        assertThat(saved).isNull()
        assertThat(library.summaries().size).isEqualTo(countBefore)
    }

    @Test
    fun tTheme34_sameIdDifferentContentReplaceOrKeepBoth() {
        import(theme(MIN_ID, "Mine", accent = "#2F5D8A"))
        val changed = theme(MIN_ID, "Mine", accent = "#8A2F2F")
        // Replace keeps the id and the name; the old bytes come back for undo.
        val c = candidate(changed)
        val plan = importer.plan(c, library.snapshot())
        assertThat(plan.conflict).isEqualTo(ImportConflict.SAME_ID)
        assertThat(plan.replaceName).isEqualTo("Mine")
        val (saved, previous) = library.commitImport(c, importer.treeFor(c, plan, ConflictChoice.REPLACE))
        assertThat(saved.document.id).isEqualTo(MIN_ID)
        assertThat(previous).isNotNull()
        assertThat(library.summaries().count { it.id == MIN_ID }).isEqualTo(1)
        assertThat(library.restore(MIN_ID)).isTrue()
        assertThat(library.restore(MIN_ID)).isFalse() // once
        assertThat(
            parseOk(library.exportJson(MIN_ID)!!)
                .document.colors.light.accent,
        ).isEqualTo(0xFF2F5D8A.toInt())

        // Keep both: new UUID, basedOn = original, name "Mine (2)".
        val (conflict, copyId) = import(changed, ConflictChoice.KEEP_BOTH)
        assertThat(conflict).isEqualTo(ImportConflict.SAME_ID)
        assertThat(copyId).isNotEqualTo(MIN_ID)
        val copy = library.loadTheme(copyId!!) as Outcome.Ok
        assertThat(copy.value.document.basedOn).isEqualTo(MIN_ID)
        assertThat(copy.value.document.name).isEqualTo("Mine (2)")
    }

    @Test
    fun tTheme34_builtInIdIsAlwaysKeptAsACopy() {
        val bytes = theme(paperId, "Paper", accent = "#112233")
        val c = candidate(bytes)
        val plan = importer.plan(c, library.snapshot())
        assertThat(plan.conflict).isEqualTo(ImportConflict.BUILT_IN_ID)
        // Even if the UI asked for Replace, the built-in is never overwritten.
        val (saved, previous) = library.commitImport(c, importer.treeFor(c, plan, ConflictChoice.REPLACE))
        assertThat(previous).isNull()
        assertThat(saved.document.id).isNotEqualTo(paperId)
        assertThat(saved.document.basedOn).isEqualTo(paperId)
        assertThat(saved.document.name).isEqualTo("Paper (2)")
        assertThat(library.isBuiltIn(paperId)).isTrue()
        assertThat(files.files.keys).doesNotContain(ThemeLibrary.userFile(paperId))
    }

    @Test
    fun tTheme34_sameNameDifferentIdIsNumbered() {
        import(theme("aaaaaaaa-0000-4000-8000-000000000001", "Night"))
        val (_, second) = import(theme("aaaaaaaa-0000-4000-8000-000000000002", "Night"))
        val (_, third) = import(theme("aaaaaaaa-0000-4000-8000-000000000003", "night"))
        assertThat(name(second!!)).isEqualTo("Night (2)")
        assertThat(name(third!!)).isEqualTo("night (3)")
        assertThat((library.loadTheme(second) as Outcome.Ok).value.document.name).isEqualTo("Night (2)")
    }

    // ---- T-THEME-35 ---------------------------------------------------------------------------------

    @Test
    fun tTheme35_libraryOperations() {
        repeat(500) { i -> library.save(json(String(theme("%08d-0000-4000-8000-000000000000".format(i), "T$i")))) }
        val first = "00000007-0000-4000-8000-000000000000"
        // Duplicate.
        val dup = library.duplicate(first, ids())!!
        assertThat(name(dup)).isEqualTo("T7 (copy)")
        assertThat((library.loadTheme(dup) as Outcome.Ok).value.document.basedOn).isEqualTo(first)
        // Editing a built-in creates "… (copy)".
        val paperCopy = library.duplicate(paperId, ids())!!
        assertThat(name(paperCopy)).isEqualTo("Paper (copy)")
        assertThat(library.isBuiltIn(paperCopy)).isFalse()
        // Hide a built-in.
        library.setHidden(paperId, true)
        assertThat(library.summaries().first { it.id == paperId }.hidden).isTrue()
        val reopened = ThemeLibrary(files, indexStore, MapBuiltInThemeSource(ThemeFixtures.samples()), clock)
        reopened.boot()
        assertThat(reopened.summaries().first { it.id == paperId }.hidden).isTrue()
        // Deleting the active theme switches to last-known-good first.
        library.apply(dup)
        library.onFirstFrame()
        library.onStableRender()
        library.apply(first)
        assertThat(library.switchAwayFrom(first)).isEqualTo(dup)
        library.delete(first)
        assertThat(library.currentIndex.activeThemeId).isEqualTo(dup)
        assertThat(library.summaries().none { it.id == first }).isTrue()
        // Deleting the active theme that is also last-known-good falls back to Strata Default.
        assertThat(library.switchAwayFrom(dup)).isEqualTo(StrataDefault.ID)
    }

    // ---- T-THEME-28 (library half) -------------------------------------------------------------------

    @Test
    fun tTheme28_bundleExportReimportsByteIdenticalOnACleanLibrary() {
        val (_, id) = import(BundleFixtures.bundle())
        val font = files.files.entries.single { it.key.startsWith("fonts/") }
        val image = files.files.entries.single { it.key.startsWith("images/") }
        assertThat(library.hasBundleMedia(id!!)).isTrue()
        val zip = (library.exportBundle(id) as BundleExport.Ok).bytes

        val clean = InMemoryThemeFileStore()
        val lib2 = ThemeLibrary(clean, InMemoryThemeIndexStore(), MapBuiltInThemeSource(ThemeFixtures.samples()), clock)
        lib2.boot()
        val c = candidate(zip)
        val plan = importer.plan(c, lib2.snapshot())
        assertThat(plan.conflict).isEqualTo(ImportConflict.NONE)
        val (saved, _) = lib2.commitImport(c, importer.treeFor(c, plan, ConflictChoice.KEEP_BOTH))
        assertThat(saved.document.id).isEqualTo(id)
        assertThat(clean.files[font.key]).isEqualTo(font.value)
        assertThat(clean.files[image.key]).isEqualTo(image.value)
        assertThat(
            saved.document.wallpaper.image!!
                .sha256,
        ).isEqualTo(BundleCodec.sha256(image.value))
    }

    @Test
    fun orphanedMediaIsGarbageCollectedOnDelete() {
        val (_, id) = import(BundleFixtures.bundle())
        assertThat(files.files.keys.any { it.startsWith("fonts/") }).isTrue()
        library.delete(id!!)
        assertThat(files.files.keys.none { it.startsWith("fonts/") || it.startsWith("images/") }).isTrue()
    }

    @Test
    fun importsAreStoredAsImported() {
        val (_, id) = import(theme(MIN_ID, "Mine"))
        assertThat(library.currentIndex.entry(id!!)!!.origin).isEqualTo(ThemeOrigin.IMPORTED)
    }

    // ---- review fixes: Replace undo keeps its media, latest version wins, pins -------------------------

    /** A bundle whose theme (id [MIN_ID]) uses its own font and wallpaper; [salt] makes the media unique. */
    private fun bundleV(
        salt: Int,
        name: String = "Bundled",
    ): ByteArray {
        val font = BundleFixtures.font(salt = salt)
        val image = BundleFixtures.webpHeader(10 + salt, 10)
        val theme = BundleFixtures.theme(BundleFixtures.sha256(font), BundleFixtures.sha256(image), name, MIN_ID)
        return BundleFixtures.bundle(font = font, image = image, themeBytes = theme)
    }

    private fun replace(bytes: ByteArray) {
        val c = candidate(bytes)
        val plan = importer.plan(c, library.snapshot())
        assertThat(plan.conflict).isEqualTo(ImportConflict.SAME_ID)
        library.commitImport(c, importer.treeFor(c, plan, ConflictChoice.REPLACE))
    }

    private fun media() =
        files.files.keys
            .filter { it.startsWith("fonts/") || it.startsWith("images/") }
            .toSet()

    @Test
    fun replaceThenUndoKeepsTheOldVersionsMedia() {
        import(bundleV(1))
        val v1Media = media()
        assertThat(v1Media).hasSize(2)
        replace(bundleV(2))
        // v1's font and image are still on disk while Undo is possible.
        assertThat(media()).containsAtLeastElementsIn(v1Media)
        assertThat(library.restore(MIN_ID)).isTrue()
        assertThat(media()).isEqualTo(v1Media) // v2's media is now unreferenced and collected
        val restored = (library.loadTheme(MIN_ID) as Outcome.Ok).value
        val font = ThemeImporter.userFonts(restored).single()
        assertThat(media()).contains("fonts/$font.ttf")
        assertThat(media()).contains("images/${restored.document.wallpaper.image!!.sha256}.webp")
    }

    @Test
    fun undoAfterASecondReplaceRestoresTheVersionJustReplaced() {
        import(bundleV(1, "v1"))
        replace(bundleV(2, "v2"))
        replace(bundleV(3, "v3"))
        assertThat(library.restore(MIN_ID)).isTrue()
        assertThat((library.loadTheme(MIN_ID) as Outcome.Ok).value.document.name).isEqualTo("v2")
        // Only v2's media and nothing of v3 remain.
        val v2 = (library.loadTheme(MIN_ID) as Outcome.Ok).value
        assertThat(media()).containsExactly(
            "fonts/${ThemeImporter.userFonts(v2).single()}.ttf",
            "images/${v2.document.wallpaper.image!!.sha256}.webp",
        )
    }

    @Test
    fun editOrDeleteDropsThePendingUndo() {
        import(bundleV(1))
        replace(bundleV(2))
        assertThat(library.canRestore(MIN_ID)).isTrue()
        library.save((library.loadTheme(MIN_ID) as Outcome.Ok).value.tree) // an edit
        assertThat(library.canRestore(MIN_ID)).isFalse()
        replace(bundleV(3))
        library.delete(MIN_ID)
        assertThat(library.canRestore(MIN_ID)).isFalse()
        assertThat(media()).isEmpty()
    }

    @Test
    fun pinnedMediaSurvivesGarbageCollection() {
        val (_, id) = import(BundleFixtures.bundle())
        val kept = media()
        library.pinnedMedia = { kept.map { it.substringAfter('/').substringBefore('.') }.toSet() }
        library.delete(id!!)
        assertThat(media()).isEqualTo(kept)
        library.pinnedMedia = { emptySet() }
        library.collectGarbage()
        assertThat(media()).isEmpty()
    }

    @Test
    fun bundleExportOverTheImportLimitsFails() {
        // Valid stored media whose sum exceeds the 24 MiB import limit: 16 MiB image + two 4 MiB fonts + the theme.
        val image = BundleFixtures.webpHeader(100, 100) + ByteArray(16 * 1024 * 1024 - 64)
        val fontA = BundleFixtures.font(salt = 1) + ByteArray(4 * 1024 * 1024 - 200)
        val fontB = BundleFixtures.font(salt = 2) + ByteArray(4 * 1024 * 1024 - 200)
        val (img, fa, fb) = listOf(image, fontA, fontB).map(BundleFixtures::sha256)
        files.files["images/$img.webp"] = image
        files.files["fonts/$fa.ttf"] = fontA
        files.files["fonts/$fb.ttf"] = fontB
        library.save(
            json(
                """{"schemaVersion":1,"id":"$MIN_ID","name":"Huge",
                   "wallpaper":{"show":true,"image":{"sha256":"$img","mediaType":"image/webp"}},
                   "typography":{"family":{"kind":"user","sha256":"$fa"},"clockFamily":{"kind":"user","sha256":"$fb"}}}""",
            ),
        )
        assertThat(library.exportBundle(MIN_ID)).isEqualTo(BundleExport.TooLarge)
        assertThat(library.exportBundle("ffffffff-0000-4000-8000-000000000000")).isEqualTo(BundleExport.NotFound)
        // Without the second font it fits again.
        files.files.remove("fonts/$fb.ttf")
        assertThat(library.exportBundle(MIN_ID)).isInstanceOf(BundleExport.Ok::class.java)
    }
}
