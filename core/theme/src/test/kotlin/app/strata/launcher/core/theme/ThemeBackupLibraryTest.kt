package app.strata.launcher.core.theme

import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.theme.store.StoredThemeFile
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeOrigin
import app.strata.launcher.core.theme.store.ThemeRestoreTarget
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.InMemoryThemeFileStore
import app.strata.launcher.testing.fakes.InMemoryThemeIndexStore
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The library half of backup and restore (ARCHITECTURE §12.3 step 3d, THEME_SYSTEM §11.4; T-THEME-41, T-BACKUP-09). */
class ThemeBackupLibraryTest {
    private val files = InMemoryThemeFileStore()
    private val indexStore = InMemoryThemeIndexStore()
    private val clock = FakeClock(1_800_000_000_000)
    private val builtIns = MapBuiltInThemeSource(ThemeFixtures.samples())

    private fun process() = ThemeLibrary(files, indexStore, builtIns, clock).also { it.boot() }

    private fun theme(
        id: String,
        name: String,
    ): JsonObject = json("""{"schemaVersion":1,"id":"$id","name":"$name","colors":{"light":{"accent":"#2F5D8A"}}}""")

    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val idC = "cccccccc-0000-4000-8000-000000000003"
    private val paperId = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"

    @Test
    fun backupData_listsStoredThemesVerbatim_andTheActiveOne() {
        val lib = process()
        lib.save(theme(idA, "A").with("x-extra", json("""{"k":1}""")))
        lib.apply(idA)
        lib.setHidden(paperId, true)
        val data = lib.backupData()
        assertThat(data.activeId).isEqualTo(idA)
        assertThat(data.themes.single().tree["x-extra"]).isEqualTo(json("""{"k":1}"""))
        assertThat(data.hiddenBuiltins).containsExactly(paperId)
        assertThat(data.builtInIds).contains(paperId)
    }

    @Test
    fun restore_addsAndReplaces_keepsOthers_andAppliesActive() {
        val lib = process()
        lib.save(theme(idA, "Old A"))
        lib.save(theme(idC, "Keep C"))
        val next =
            lib.restoreThemes(
                ThemeRestoreTarget(
                    themes =
                        listOf(
                            StoredThemeFile(idA, theme(idA, "New A")),
                            StoredThemeFile(idB, theme(idB, "B")),
                            // A built-in id is never written.
                            StoredThemeFile(paperId, theme(paperId, "Not paper")),
                            StoredThemeFile(
                                "dddddddd-0000-4000-8000-000000000004",
                                json(
                                    """{"schemaVersion":7,"id":"dddddddd-0000-4000-8000-000000000004","name":"Future"}""",
                                ),
                                needsAppUpdate = true,
                            ),
                        ),
                    activeId = idB,
                    hiddenBuiltins = setOf(paperId),
                    exact = false,
                ),
            )
        assertThat(next).isEqualTo(idB)
        val names = lib.summaries().associate { it.id.lowercase() to it }
        assertThat(names.getValue(idA).name).isEqualTo("New A")
        assertThat(names.getValue(idC).name).isEqualTo("Keep C")
        assertThat(names.getValue(idB).name).isEqualTo("B")
        assertThat(names.getValue(paperId).name).isNotEqualTo("Not paper")
        assertThat(names.getValue(paperId).hidden).isTrue()
        assertThat(names.getValue("dddddddd-0000-4000-8000-000000000004").needsAppUpdate).isTrue()
        assertThat(lib.currentIndex.activeThemeId).isEqualTo(idB)
        // A new process sees the same library (files and index written).
        assertThat(process().summaries().map { it.id.lowercase() }).containsAtLeast(idA, idB, idC)
    }

    @Test
    fun exactRestore_deletesThemesNotInTheTarget_andFallsBackWhenActiveGoes() {
        val lib = process()
        lib.save(theme(idA, "A"))
        lib.save(theme(idB, "B"))
        lib.apply(idB)
        lib.restoreThemes(
            ThemeRestoreTarget(listOf(StoredThemeFile(idA, theme(idA, "A"))), null, emptySet(), exact = true),
        )
        assertThat(lib.summaries().filterNot { it.builtIn }.map { it.id.lowercase() }).containsExactly(idA)
        assertThat(lib.currentIndex.activeThemeId).isEqualTo(StrataDefault.ID)
        assertThat(files.read(ThemeLibrary.userFile(idB))).isNull()
    }

    @Test
    fun restore_ignoresUnsafeIds() {
        val lib = process()
        lib.restoreThemes(
            ThemeRestoreTarget(
                listOf(
                    StoredThemeFile(
                        "../../evil",
                        json("""{"schemaVersion":9,"id":"../../evil"}"""),
                        needsAppUpdate = true,
                    ),
                ),
                null,
                null,
                exact = false,
            ),
        )
        assertThat(files.list(ThemeLibrary.USER_DIR)).isEmpty()
    }

    /** T-THEME-41 (JVM half): after an Auto Backup restore the valid file stays, the corrupted one is quarantined. */
    @Test
    fun tTheme41_revalidation_quarantinesTheCorruptedFile() {
        val lib = process()
        lib.save(theme(idA, "Valid"))
        lib.save(theme(idB, "Will break"))
        lib.apply(idB)
        // The restored index still lists both; the file of B came back damaged.
        files.write(ThemeLibrary.userFile(idB), "{ not json".toByteArray())
        val again = process()
        assertThat(again.currentIndex.entry(idB)).isNotNull()
        assertThat(again.revalidateStored()).isEqualTo(1)
        assertThat(again.summaries().filterNot { it.builtIn }.map { it.id.lowercase() }).containsExactly(idA)
        assertThat(files.list(ThemeLibrary.QUARANTINE_DIR)).hasSize(1)
        assertThat(again.currentIndex.activeThemeId).isEqualTo(StrataDefault.ID)
        assertThat(again.currentIndex.entry(idA)!!.origin).isEqualTo(ThemeOrigin.USER)
    }
}
