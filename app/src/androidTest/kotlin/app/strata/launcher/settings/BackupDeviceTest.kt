package app.strata.launcher.settings

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.backup.BackupCodec
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.platformapi.backup.ExportOptions
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.feature.widgets.WidgetTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.shell
import app.strata.launcher.testing.fixtures.BackupFixtures
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Backup and restore on a device (level I) through the production graph: T-BACKUP-01, T-BACKUP-04, T-WIDGET-10 and
 * the export half of T-PRIV-04. The system document picker cannot be driven on GMD, so documents are our own
 * `FileProvider` URIs under `cacheDir/share/` (the same `DocumentIO` path as a SAF document). `bmgr` (T-BACKUP-11,
 * T-WIDGET-11, T-THEME-41) and the two-device round trip (T-BACKUP-13) are manual checks on the A56 (M8 notes).
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val graph get() = context.appGraph
    private val device = UiDevice.getInstance(instrumentation)
    private val dir get() = File(context.cacheDir, "share/backup-test")

    private var original: Snapshot? = null

    /** What the tests change, to put it back (global state, any test order). */
    private data class Snapshot(
        val favorites: List<AppKey>,
        val settings: Settings,
        val label: String?,
    )

    private fun self(): AppKey =
        runBlocking {
            val main =
                withTimeout(10_000) {
                    graph.profiles.profiles.first { l ->
                        l.any { it.kind == ProfileKind.MAIN }
                    }
                }
            AppKey(
                "${context.packageName}/${SettingsActivity::class.java.name}",
                main
                    .first {
                        it.kind ==
                            ProfileKind.MAIN
                    }.serial,
            )
        }

    private fun uri(name: String): String {
        dir.mkdirs()
        val f = File(dir, name).apply { if (!exists()) writeBytes(ByteArray(0)) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f).toString()
    }

    private fun bytes(name: String): ByteArray = File(dir, name).readBytes()

    @Before
    fun before() =
        runBlocking {
            graph.startCatalog()
            val key = self()
            original =
                Snapshot(
                    favorites = graph.favorites.favorites.first(),
                    settings = graph.settingsRepository.settings.first(),
                    label =
                        graph.overrides.overrides
                            .first()[key]
                            ?.customLabel,
                )
        }

    @After
    fun after() =
        runBlocking {
            // Undo any restore of this test, then put back what the test changed directly.
            if (graph.backup.undoAvailable.value) graph.backup.undoRestore()
            val o = original ?: return@runBlocking
            val key = self()
            graph.favorites.saveEdit(
                o.favorites,
                graph.favorites.favorites
                    .first()
                    .toSet(),
            )
            graph.overrides.setCustomLabel(key, ProfileKind.MAIN, o.label)
            graph.settingsRepository.update { o.settings.copy(installId = it.installId) }
            dir.deleteRecursively()
        }

    /** T-BACKUP-01 (I): the default export validates and holds no work entries, no launch log, nothing private. */
    @Test
    fun tBackup01_exportContent() =
        runBlocking {
            val key = self()
            graph.favorites.add(key) { true }
            graph.overrides.setCustomLabel(key, ProfileKind.MAIN, "Renamed for backup")
            val name = graph.backup.suggestedFileName()
            assertThat(name).matches("strata-\\d{4}-\\d{2}-\\d{2}\\.strata-backup\\.json")
            val r = graph.backup.export(uri(name), ExportOptions())
            assertWithMessage("export: $r").that(r).isInstanceOf(Outcome.Ok::class.java)
            val b = bytes(name)
            assertThat(BackupCodec.decode(b)).isInstanceOf(Outcome.Ok::class.java)
            val root =
                (
                    JsonParser.parse(
                        b,
                        app.strata.launcher.backup.BackupFormat.LIMITS,
                    ) as Outcome.Ok
                ).value as JsonObject
            assertThat(root.keys).doesNotContain("launchLog")
            val text = b.toString(Charsets.UTF_8)
            assertThat(text).contains("Renamed for backup")
            assertThat(text).doesNotContain("\"profile\": \"work\"")
            // Private space (API 35+, unlocked): none of its components is in the file.
            val privateComponents =
                graph.appCatalog.catalog.value.entries
                    .filter { it.profileKind == ProfileKind.PRIVATE }
                    .map { it.key.component }
            for (c in privateComponents) {
                // The same component may exist in the main profile; only a private-only one must be absent.
                if (graph.appCatalog.catalog.value.entries.none {
                        it.key.component == c &&
                            it.profileKind != ProfileKind.PRIVATE
                    }
                ) {
                    assertThat(text).doesNotContain(c)
                }
            }
        }

    /** T-BACKUP-04 (I): restore B (state S1) over S2, then undo: S1, then S2 again (DB, settings, themes). */
    @Test
    fun tBackup04_restoreAndUndo() =
        runBlocking {
            val key = self()
            graph.overrides.setCustomLabel(key, ProfileKind.MAIN, "State one")
            graph.settingsRepository.update { it.copy(listTwoColumn = false) }
            val s1 = dump()
            assertThat(
                graph.backup.export(uri("b.json"), ExportOptions(workProfile = true)),
            ).isInstanceOf(Outcome.Ok::class.java)
            graph.overrides.setCustomLabel(key, ProfileKind.MAIN, "State two")
            graph.settingsRepository.update { it.copy(listTwoColumn = true) }
            val s2 = dump()
            assertThat(s2).isNotEqualTo(s1)

            val preview = (graph.backup.preview(uri("b.json")) as Outcome.Ok).value
            assertThat(dump()).isEqualTo(s2)
            assertThat(graph.backup.restore(preview.token)).isInstanceOf(Outcome.Ok::class.java)
            assertThat(dump()).isEqualTo(s1)
            assertThat(graph.backup.undoRestore()).isEqualTo(Outcome.Ok(Unit))
            assertThat(dump()).isEqualTo(s2)
        }

    /** T-WIDGET-10 (I): a restored widget placement is the "Re-add" placeholder on home. */
    @Test
    fun tWidget10_restoredWidgetShowsReAdd() =
        runBlocking {
            File(dir.apply { mkdirs() }, "w.json").writeBytes(BackupFixtures.validBytes())
            val preview = (graph.backup.preview(uri("w.json")) as Outcome.Ok).value
            assertThat(preview.widgetsToReAdd).isEqualTo(1)
            assertThat(graph.backup.restore(preview.token)).isInstanceOf(Outcome.Ok::class.java)
            val placement = graph.widgets.current()
            assertThat(placement?.state).isEqualTo(WidgetPlacementState.NEEDS_REBIND)
            graph.settingsRepository.update { it.copy(onboardingDone = true) }
            shell(
                "am start -n ${context.packageName}/.home.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME",
            )
            assertWithMessage("no Re-add placeholder. ${focusReport()}")
                .that(device.wait(Until.hasObject(By.res(WidgetTestTags.READD)), 15_000))
                .isTrue()
        }

    /**
     * T-PRIV-04 (I, search + export half): after a search session with "zz-secret" and an export, neither the app's
     * data directories nor the export contain the query or a private-space-only component. The notification half
     * (T-NOTIF-07) and the private-space session (T-APPS-16) are their own device tests, run before this one in a pass.
     */
    @Test
    fun tPriv04_noSecretInDataOrExport() =
        runBlocking {
            graph.settingsRepository.update { it.copy(onboardingDone = true) }
            shell(
                "am start -n ${context.packageName}/.home.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME",
            )
            assertWithMessage("home not shown. ${focusReport()}")
                .that(device.wait(Until.hasObject(By.res(HomeTestTags.ROOT)), 15_000))
                .isTrue()
            // Typing on home opens search with the typed query (T-SEARCH-02).
            shell("input text zz-secret")
            device.wait(Until.hasObject(By.res(SearchTestTags.LAYER)), 10_000)
            device.pressBack()
            device.pressBack()
            assertThat(graph.backup.export(uri("p.json"), ExportOptions(workProfile = true, launchLog = true)))
                .isInstanceOf(Outcome.Ok::class.java)
            val export = bytes("p.json").toString(Charsets.ISO_8859_1)
            assertThat(export).doesNotContain("zz-secret")
            val privateOnly =
                graph.appCatalog.catalog.value.entries
                    .filter { it.profileKind == ProfileKind.PRIVATE }
                    .map { it.key.component }
                    .filter { c ->
                        graph.appCatalog.catalog.value.entries.none {
                            it.key.component == c &&
                                it.profileKind != ProfileKind.PRIVATE
                        }
                    }
            for (c in privateOnly) assertThat(export).doesNotContain(c)
            // The test documents live in cacheDir/share/backup-test (our own files); everything else is scanned.
            val roots =
                listOf(
                    context.filesDir,
                    context.noBackupFilesDir,
                    context.getDatabasePath("x").parentFile!!,
                    context.cacheDir,
                )
            val hits =
                roots.flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile && !it.path.startsWith(dir.path) && it.length() < 32L * 1024 * 1024 }
                        .filter { f ->
                            val text = f.readBytes().toString(Charsets.ISO_8859_1)
                            text.contains("zz-secret") || privateOnly.any { text.contains(it) }
                        }.map { it.path }
                        .toList()
                }
            assertWithMessage("sensitive data found").that(hits).isEmpty()
        }

    /** The comparable state of T-BACKUP-04: overrides and favourites, exported settings, theme library and active id. */
    private suspend fun dump(): List<Any?> {
        val s = graph.settingsRepository.settings.first()
        return listOf(
            graph.overrides.overrides.first().filterValues { it.missingSince == null }.mapValues { (_, o) ->
                o.copy(missingSince = null)
            },
            graph.favorites.favorites.first(),
            s.listTwoColumn,
            s.hapticsEnabled,
            s.badgeStyle,
            s.appearanceOverride,
            // Widgets come back as placeholders by design (their IDs cannot travel), so they are not compared here.
            graph.themeManager.entries().map { it.id to it.name },
        )
    }
}
