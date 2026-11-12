package app.strata.launcher.backup

import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.theme.store.ThemeBackupData
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeBackupAppStore
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeDocumentIO
import app.strata.launcher.testing.fakes.FakeInstallMarker
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeProfileSource
import app.strata.launcher.testing.fakes.FakeRestoreFiles
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeThemeBackupStore
import app.strata.launcher.testing.fakes.RecordingLogger
import kotlinx.coroutines.Dispatchers
import java.time.ZoneOffset

/** Every store the backup service writes, in memory; [service] builds a fresh service (a "new process") over them. */
class BackupHarness(
    val clock: FakeClock = FakeClock(1_790_000_000_000L),
) {
    val documents = FakeDocumentIO()
    val apps = FakeBackupAppStore()
    val settings = FakeSettingsRepository(Settings(installId = "8b2a3c4d-1e2f-4a5b-8c6d-7e8f9a0b1c2d"))
    val themes = FakeThemeBackupStore(builtInIds = setOf(BUILTIN))
    val profiles =
        FakeProfileSource(
            listOf(
                Profile(MAIN, ProfileKind.MAIN, quietMode = false, available = true),
                Profile(PRIVATE, ProfileKind.PRIVATE, quietMode = false, available = true),
            ),
        )
    val catalog = FakeAppCatalog()
    val launchLog = FakeLaunchLogRepository(clock)
    val files = FakeRestoreFiles()
    val marker = FakeInstallMarker()
    val logger = RecordingLogger()
    private var ids = 0

    fun service(): DefaultBackupService =
        DefaultBackupService(
            documents = documents,
            apps = apps,
            settings = settings,
            themes = themes,
            profiles = profiles,
            catalog = catalog,
            launchLog = launchLog,
            files = files,
            marker = marker,
            clock = clock,
            io = Dispatchers.Unconfined,
            logger = logger,
            appVersion = "1.0.0-test",
            zone = { ZoneOffset.UTC },
            newId = { "00000000-0000-4000-8000-${(++ids).toString().padStart(12, '0')}" },
            profileTimeoutMs = 100,
        )

    fun install(vararg components: String, serial: Long = MAIN, kind: ProfileKind = ProfileKind.MAIN) {
        val add =
            components.map { AppEntry(AppKey(it, serial), it.substringBefore('/'), it.substringAfterLast('.'), kind) }
        catalog.catalog.value = Catalog(catalog.catalog.value.entries + add, fromSnapshot = false)
    }

    /** The canonical dump of T-BACKUP-04: DB rows, exported settings, themes. */
    data class Dump(
        val apps: AppData,
        val settings: Settings,
        val themes: ThemeBackupData,
    )

    suspend fun dump(): Dump =
        Dump(
            apps.read(),
            SettingsCodec.copyExported(settings.value, Settings.DEFAULT),
            themes.backupData().let { it.copy(themes = it.themes.sortedBy { t -> t.id }) },
        )

    companion object {
        const val MAIN = 0L
        const val WORK = 10L
        const val PRIVATE = 11L
        const val BUILTIN = "a413ab93-de91-4614-8c5c-cfbe6d79231b"
    }
}
