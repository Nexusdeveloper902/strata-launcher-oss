package app.strata.launcher.core.theme

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.json.JsonWriter
import app.strata.launcher.core.theme.parse.ThemeError
import app.strata.launcher.core.theme.parse.ThemeMigration
import app.strata.launcher.core.theme.parse.ThemeMigrations
import app.strata.launcher.core.theme.parse.ThemeParser
import app.strata.launcher.core.theme.store.BootSource
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeOrigin
import app.strata.launcher.core.theme.write.ThemeWriter
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.InMemoryThemeFileStore
import app.strata.launcher.testing.fakes.InMemoryThemeIndexStore
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fakes.SimulatedKill
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * T-THEME-40 (migration framework and `.bak`), FR-THEME-28 recovery (LKG, corrupted files/index,
 * crash-loop guard) and the JVM half of T-THEME-45 (atomicity under simulated kills).
 */
class ThemeLibraryTest {
    private val files = InMemoryThemeFileStore()
    private val indexStore = InMemoryThemeIndexStore()
    private val clock = FakeClock(1_800_000_000_000)
    private val builtIns = MapBuiltInThemeSource(ThemeFixtures.samples())

    /** A new "process": fresh library over the same storage. */
    private fun process(parser: ThemeParser = ThemeParser()) = ThemeLibrary(files, indexStore, builtIns, clock, parser)

    private fun userTheme(
        id: String,
        name: String,
        accent: String = "#2F5D8A",
    ): JsonObject = json("""{"schemaVersion":1,"id":"$id","name":"$name","colors":{"light":{"accent":"$accent"}}}""")

    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val paperId = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"

    // ---- T-THEME-40 -------------------------------------------------------------------------------

    /** Synthetic v0 → v1 migrator: `title` → `name`, `palette` → `colors.light`, `listDensity` → `list.density`. */
    private object V0ToV1 : ThemeMigration {
        override val fromVersion = 0

        override fun migrate(tree: JsonObject): JsonObject {
            val out = LinkedHashMap<String, app.strata.launcher.core.json.JsonValue>()
            for ((k, v) in tree.entries) {
                when (k) {
                    "title" -> out["name"] = v
                    "palette" -> out["colors"] = JsonObject("light" to v)
                    "listDensity" -> out["list"] = JsonObject("density" to v)
                    else -> out[k] = v
                }
            }
            return JsonObject(out)
        }
    }

    private val chain = ThemeMigrations(listOf(V0ToV1))

    private fun resource(path: String): ByteArray = javaClass.classLoader.getResourceAsStream(path)!!.readBytes()

    @Test
    fun `T-THEME-40 frozen fixtures - migration output equals the expected file`() {
        for (n in 1..2) {
            val input = json(String(resource("migrations/v0-to-v1/input-$n.json")))
            val expected = json(String(resource("migrations/v0-to-v1/expected-$n.json")))
            val migrated = chain.migrate(input, 0)
            assertThat(migrated).isEqualTo(expected)
            assertThat(migrated.keys).containsExactlyElementsIn(expected.keys).inOrder()
        }
        // The production chain is empty: schemaVersion 0 is "not a Strata theme" in v1.
        assertThat(ThemeMigrations.V1.oldestSupported).isEqualTo(1)
        assertThat(
            ThemeParser().parse(resource("migrations/v0-to-v1/input-1.json")),
        ).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    fun `T-THEME-40 a migrated file loads in memory, keeps a bak on rewrite until the next successful boot`() {
        val parser = ThemeParser(migrations = chain)
        val original = resource("migrations/v0-to-v1/input-1.json")
        val id = "0a0b0c0d-0e0f-4a1b-8c2d-3e4f5a6b7c8d"
        files.files[ThemeLibrary.userFile(id)] = original
        val bak = "user/$id.v0.bak"

        // Boot 1: loaded through the migration, nothing rewritten.
        val p1 = process(parser)
        p1.boot()
        val loaded = (p1.loadTheme(id) as Outcome.Ok).value
        assertThat(loaded.migratedFrom).isEqualTo(0)
        assertThat(loaded.document.name).isEqualTo("Synthetic v0 theme")
        assertThat(files.files[ThemeLibrary.userFile(id)]).isEqualTo(original)
        assertThat(files.files).doesNotContainKey(bak)

        // First save rewrites the file as v1 and keeps the original as .bak.
        p1.save(loaded.tree.with("name", JsonString("Edited")))
        assertThat(files.files[bak]).isEqualTo(original)
        val rewritten = (parser.parse(files.files[ThemeLibrary.userFile(id)]!!) as Outcome.Ok).value
        assertThat(rewritten.migratedFrom).isNull()
        p1.onBootSucceeded() // the boot that loaded it predates the rewrite: the .bak stays
        assertThat(files.files).containsKey(bak)

        // Boot 2: the .bak survives until this boot is known good, then goes.
        val p2 = process(parser)
        p2.boot()
        assertThat(files.files).containsKey(bak)
        p2.onFirstFrame()
        p2.onBootSucceeded()
        assertThat(files.files).doesNotContainKey(bak)
    }

    // ---- FR-THEME-28: boot and recovery -------------------------------------------------------------

    private fun installAndApply(
        id: String,
        name: String,
    ): ThemeLibrary {
        val lib = process()
        lib.boot()
        lib.save(userTheme(id, name))
        lib.apply(id)
        lib.onFirstFrame()
        lib.onStableRender()
        return lib
    }

    @Test
    fun `fresh install boots Strata Default without banner`() {
        val r = process().boot()
        assertThat(r.source).isEqualTo(BootSource.STRATA_DEFAULT)
        assertThat(r.showBanner).isFalse()
        assertThat(r.document).isEqualTo(StrataDefault.document)
    }

    @Test
    fun `active user theme and built-in themes load`() {
        installAndApply(idA, "A")
        assertThat(process().boot().let { it.source to it.document.name }).isEqualTo(BootSource.ACTIVE to "A")
        val lib = process()
        lib.boot()
        lib.apply(paperId)
        lib.onFirstFrame()
        assertThat(process().boot().document.name).isEqualTo("Paper")
        assertThat(lib.isBuiltIn(paperId)).isTrue()
    }

    @Test
    fun `FR-THEME-28 corrupt active file falls back to last-known-good with banner`() {
        installAndApply(idA, "A")
        val lib = process()
        lib.boot()
        lib.save(userTheme(idB, "B"))
        lib.apply(idB)
        lib.onFirstFrame() // B applied but never reached a stable render: LKG is still A
        files.files[ThemeLibrary.userFile(idB)] = "{ broken".toByteArray()
        val r = process().boot()
        assertThat(r.source).isEqualTo(BootSource.LAST_KNOWN_GOOD)
        assertThat(r.document.name).isEqualTo("A")
        assertThat(r.showBanner).isTrue()
        assertThat(r.problem).isInstanceOf(ThemeError.Damaged::class.java)
    }

    @Test
    fun `FR-THEME-28 corrupt active and LKG fall back to Strata Default with banner`() {
        installAndApply(idA, "A")
        files.files[ThemeLibrary.userFile(idA)] = byteArrayOf(0xFF.toByte())
        files.files[ThemeLibrary.LKG] = "[]".toByteArray()
        val r = process().boot()
        assertThat(r.source).isEqualTo(BootSource.STRATA_DEFAULT)
        assertThat(r.showBanner).isTrue()
        // Unreadable storage (I/O errors) is handled the same way.
        files.unreadable += ThemeLibrary.userFile(idA)
        assertThat(process().boot().source).isEqualTo(BootSource.STRATA_DEFAULT)
    }

    @Test
    fun `FR-THEME-28 corrupt index is rebuilt from files and boots from LKG with banner`() {
        installAndApply(idA, "A")
        process().also { it.boot() }.save(userTheme(idB, "B"))
        indexStore.corrupt = true
        val lib = process()
        val r = lib.boot()
        assertThat(r.source).isEqualTo(BootSource.LAST_KNOWN_GOOD)
        assertThat(r.showBanner).isTrue()
        assertThat(lib.currentIndex.entries.map { it.id }).containsExactly(idA, idB)
        assertThat(indexStore.stored!!.activeThemeId).isEqualTo(idA)
        // Index and active file both corrupt: still a usable launcher.
        indexStore.corrupt = true
        files.files[ThemeLibrary.userFile(idA)] = "x".toByteArray()
        files.files[ThemeLibrary.LKG] = "x".toByteArray()
        val r2 = process().boot()
        assertThat(r2.source).isEqualTo(BootSource.STRATA_DEFAULT)
        assertThat(r2.showBanner).isTrue()
    }

    @Test
    fun `FR-THEME-28 crash-loop guard triggers after two failed starts within 5 minutes`() {
        installAndApply(idA, "A")
        val lib = process()
        lib.boot()
        lib.save(userTheme(idB, "B"))
        lib.apply(idB) // ... and the process dies before the first frame
        val s1 = process().boot()
        assertThat(s1.source).isEqualTo(BootSource.ACTIVE) // first failed start: try again
        clock.advanceBy(60_000)
        val s2 = process().boot() // second failed start within 5 min
        assertThat(s2.source).isEqualTo(BootSource.CRASH_GUARD)
        assertThat(s2.offenderId).isEqualTo(idB)
        assertThat(s2.document).isEqualTo(StrataDefault.document)
        assertThat(files.files).containsKey(ThemeLibrary.userFile(idB)) // file kept
        assertThat(indexStore.stored!!.entry(idB)!!.causedProblem).isTrue()
        // Next start is normal (Strata Default, the offender is not re-applied).
        val s3 = process().boot()
        assertThat(s3.source).isEqualTo(BootSource.STRATA_DEFAULT)
        assertThat(s3.showBanner).isFalse()
        // Re-applying it explicitly clears the mark.
        process().also { it.boot() }.apply(idB)
        assertThat(indexStore.stored!!.entry(idB)!!.causedProblem).isFalse()
    }

    @Test
    fun `crash-loop guard - starts more than 5 minutes apart do not trigger, a first frame disarms`() {
        installAndApply(idA, "A")
        val lib = process()
        lib.boot()
        lib.apply(idA)
        assertThat(process().boot().source).isEqualTo(BootSource.ACTIVE) // first failed start
        clock.advanceBy(6 * 60_000)
        val p = process()
        assertThat(p.boot().source).isEqualTo(BootSource.ACTIVE) // outside the window: counting restarts
        p.onFirstFrame()
        clock.advanceBy(1_000)
        assertThat(process().boot().source).isEqualTo(BootSource.ACTIVE)
        assertThat(indexStore.stored!!.applyInProgress).isFalse()
    }

    @Test
    fun `safe mode boots Strata Default and only marks the offender`() {
        installAndApply(idA, "A")
        val r1 = process().boot(safeMode = true)
        assertThat(r1.source).isEqualTo(BootSource.SAFE_MODE)
        assertThat(r1.offenderId).isNull() // nothing was being applied
        val lib = process()
        lib.boot()
        lib.apply(idA)
        val r2 = process().boot(safeMode = true)
        assertThat(r2.offenderId).isEqualTo(idA)
        assertThat(indexStore.stored!!.entry(idA)!!.causedProblem).isTrue()
    }

    @Test
    fun `LKG is updated only after a stable render`() {
        installAndApply(idA, "A")
        val lib = process()
        lib.boot()
        lib.save(userTheme(idB, "B"))
        lib.apply(idB)
        lib.onFirstFrame()
        assertThat(indexStore.stored!!.lastKnownGoodId).isEqualTo(idA)
        lib.onStableRender()
        assertThat(indexStore.stored!!.lastKnownGoodId).isEqualTo(idB)
        assertThat(
            (ThemeParser().parse(files.files[ThemeLibrary.LKG]!!) as Outcome.Ok).value.document.name,
        ).isEqualTo("B")
    }

    @Test
    fun `deleting the active theme switches to LKG first, built-ins cannot be written or deleted`() {
        installAndApply(idA, "A")
        val lib = process()
        lib.boot()
        lib.save(userTheme(idB, "B"))
        lib.apply(idB)
        lib.delete(idB)
        assertThat(indexStore.stored!!.activeThemeId).isEqualTo(idA)
        assertThat(files.files).doesNotContainKey(ThemeLibrary.userFile(idB))
        assertThat(runCatching { lib.delete(paperId) }.isFailure).isTrue()
        assertThat(
            runCatching { lib.save(json(String(ThemeFixtures.sample("paper.strata-theme.json")))) }.isFailure,
        ).isTrue()
    }

    @Test
    fun `reconciliation adds stray files, drops missing ones, flags newer majors and quarantines garbage`() {
        installAndApply(idA, "A")
        files.files[ThemeLibrary.userFile(idB)] = ThemeWriter.write(userTheme(idB, "B"))
        val newer = "dddddddd-0000-4000-8000-000000000004"
        files.files[ThemeLibrary.userFile(newer)] =
            """{"schemaVersion":2,"id":"$newer","name":"Future"}""".toByteArray()
        files.files["user/garbage.strata-theme.json"] = "not json".toByteArray()
        val lib = process()
        lib.boot()
        val ids = lib.currentIndex.entries.map { it.id }
        assertThat(ids).containsExactly(idA, idB, newer)
        assertThat(lib.currentIndex.entry(newer)!!.needsAppUpdate).isTrue()
        assertThat(files.files).containsKey("quarantine/garbage.strata-theme.json")
        assertThat(files.files).containsKey(ThemeLibrary.userFile(newer)) // kept on disk untouched
        // A "Needs app update" theme is not applicable: it falls back with a banner.
        lib.apply(newer)
        lib.onFirstFrame()
        val r = process().boot()
        assertThat(r.source).isEqualTo(BootSource.LAST_KNOWN_GOOD)
        assertThat(r.problem).isInstanceOf(ThemeError.NewerVersion::class.java)
        // A deleted file disappears from the index.
        files.files.remove(ThemeLibrary.userFile(idB))
        assertThat(
            process()
                .also { it.boot() }
                .currentIndex.entries
                .map { it.id },
        ).doesNotContain(idB)
    }

    @Test
    fun `save stamps timestamps and records the entry`() {
        val lib = process()
        lib.boot()
        val saved = lib.save(userTheme(idA, "A"), ThemeOrigin.IMPORTED)
        assertThat(saved.document.createdAt).isEqualTo("2027-01-15T08:00:00Z")
        assertThat(saved.document.modifiedAt).isEqualTo("2027-01-15T08:00:00Z")
        val e = lib.currentIndex.entry(idA)!!
        assertThat(e.origin).isEqualTo(ThemeOrigin.IMPORTED)
        assertThat(e.fileSha256).isEqualTo(ThemeLibrary.sha256(files.files[ThemeLibrary.userFile(idA)]!!))
        assertThat(e.contrastStatus).isEqualTo(app.strata.launcher.core.theme.contrast.ContrastStatus.AA_ANY_WALLPAPER)
    }

    // ---- T-THEME-45 (JVM half) ----------------------------------------------------------------------

    @Test
    fun `T-THEME-45 1000 save cycles with a random kill leave every theme old or new and the index reconcilable`() {
        val rnd = Random(45)
        val ids = (1..5).map { "eeeeeeee-0000-4000-8000-%012d".format(it) }
        val versions = HashMap<String, MutableSet<String>>() // id -> every accent ever attempted
        repeat(1000) { cycle ->
            val lib = process()
            lib.boot()
            val id = ids[rnd.nextInt(ids.size)]
            val accent = "#%06X".format(rnd.nextInt(0x1000000))
            val before = files.files[ThemeLibrary.userFile(id)]?.copyOf()
            versions.getOrPut(id) { HashSet() } += accent
            // Kill at a random point: during the file write, during the index write, or not at all.
            when (rnd.nextInt(3)) {
                0 -> files.killAfterWrites = 0
                1 -> indexStore.killOnWrite = true
                else -> Unit
            }
            val result = runCatching { lib.save(userTheme(id, "T$cycle", accent)) }
            files.killAfterWrites = -1
            indexStore.killOnWrite = false
            if (result.isFailure) assertThat(result.exceptionOrNull()).isInstanceOf(SimulatedKill::class.java)
            val after = files.files[ThemeLibrary.userFile(id)]
            // Old or new, never anything else.
            if (after != null && before != null && !after.contentEquals(before)) {
                val doc = (ThemeParser().parse(after) as Outcome.Ok).value.document
                assertThat(doc.name).isEqualTo("T$cycle")
            }
            if (after != null) assertThat(ThemeParser().parse(after)).isInstanceOf(Outcome.Ok::class.java)
            // Next start: the index agrees with the directory.
            val next = process()
            next.boot()
            val onDisk =
                files
                    .list(
                        "user",
                    ).filter { it.endsWith(ThemeLibrary.SUFFIX) }
                    .map { it.removeSuffix(ThemeLibrary.SUFFIX) }
            assertWithMessage("cycle $cycle")
                .that(
                    next.currentIndex.entries.map {
                        it.id.lowercase()
                    },
                ).containsExactlyElementsIn(onDisk)
        }
        assertThat(versions.keys).hasSize(5)
    }

    @Test
    fun `writer output of a saved theme is the canonical format`() {
        val lib = process()
        lib.boot()
        lib.save(userTheme(idA, "A"))
        val text = String(files.files[ThemeLibrary.userFile(idA)]!!)
        assertThat(text).isEqualTo(ThemeWriter.writeString(json(text)))
        assertThat(text).isEqualTo(JsonWriter.pretty(ThemeWriter.canonical(json(text))))
    }

    /** §11.4: never deleted silently, so a second quarantined file of the same name does not replace the first. */
    @Test
    fun `quarantine keeps an earlier file of the same name`() {
        files.files["user/garbage.strata-theme.json"] = "first".toByteArray()
        process().boot()
        files.files["user/garbage.strata-theme.json"] = "second".toByteArray()
        process().boot()
        assertThat(String(files.files["quarantine/garbage.strata-theme.json"]!!)).isEqualTo("first")
        assertThat(String(files.files["quarantine/garbage.strata-theme.json.1"]!!)).isEqualTo("second")
    }
}
