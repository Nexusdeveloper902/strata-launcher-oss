package app.strata.launcher.backup

import app.strata.launcher.backup.BackupHarness.Companion.MAIN
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.EntryKeyMigration
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.platformapi.backup.ExportOptions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * T-BACKUP-14 (FB-019): a backup made before Strata's launcher entry moved from `settings.SettingsActivity` to
 * `settings.EntryActivity` restores the entry's favourite and override (custom label, hidden flag) onto the new entry,
 * and the preview does not report Strata as a missing app. Other apps' keys are untouched.
 */
class EntryKeyRestoreTest {
    private val h = BackupHarness()
    private val pkg = "app.strata.launcher"
    private val old = AppKey("$pkg/${EntryKeyMigration.OLD_CLASS}", MAIN)
    private val new = AppKey("$pkg/${EntryKeyMigration.NEW_CLASS}", MAIN)
    private val other = AppKey("com.example.notes/.Main", MAIN)

    private fun <T> Outcome<T, BackupError>.ok(): T = (this as Outcome.Ok).value

    @Test
    fun tBackup14_oldBackupMapsTheOldEntryToTheNewOne() =
        runBlocking<Unit> {
            // A backup written by the previous version: the entry was keyed on SettingsActivity.
            h.apps.data =
                AppData(
                    overrides =
                        listOf(
                            AppOverride(old, hidden = true, customLabel = "Launcher setup"),
                            AppOverride(other, customLabel = "Notes"),
                        ),
                    favorites = listOf(other, old),
                )
            h.service().export("content://old", ExportOptions()).ok()

            // The new version: the entry is installed as EntryActivity; the stores are empty.
            h.install(new.component, other.component)
            h.apps.data = AppData()
            val service = h.service { EntryKeyMigration.component(it, pkg) }
            val preview = service.preview("content://old").ok()
            assertThat(preview.missingApps).doesNotContain(pkg)
            service.restore(preview.token).ok()

            val overrides =
                h.apps.data.overrides
                    .associateBy { it.key }
            assertThat(overrides.keys).containsExactly(new, other)
            assertThat(overrides.getValue(new).customLabel).isEqualTo("Launcher setup")
            assertThat(overrides.getValue(new).hidden).isTrue()
            assertThat(overrides.getValue(new).missingSince).isNull()
            assertThat(overrides.getValue(other).customLabel).isEqualTo("Notes")
            assertThat(h.apps.data.favorites).containsExactly(other, new).inOrder()
        }

    @Test
    fun withoutTheMigration_theOldEntryIsAMissingApp() =
        runBlocking<Unit> {
            // Control: the same backup restored without the mapping keeps the dead key (what FB-019 would have done).
            h.apps.data =
                AppData(overrides = listOf(AppOverride(old, customLabel = "Launcher setup")), favorites = listOf(old))
            h.service().export("content://old", ExportOptions()).ok()
            h.install(new.component)
            h.apps.data = AppData()
            val service = h.service()
            val preview = service.preview("content://old").ok()
            assertThat(preview.missingApps).contains(pkg)
            service.restore(preview.token).ok()
            assertThat(h.apps.data.favorites).containsExactly(old)
        }
}
