package app.strata.launcher.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.backup.BackupCounts
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.platformapi.backup.ExportOptions
import app.strata.launcher.core.platformapi.backup.RestorePreview
import app.strata.launcher.feature.settings.BackupTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.testing.fakes.FakeBackupService
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Settings → Backup (J-11; levels C/R): T-BACKUP-03, the C halves of T-BACKUP-05/06, preview → replace → undo. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h5000dp")
class BackupSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = FakeBackupService()

    private fun graph() =
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
            backup = service,
        ).install()

    private fun open(): ActivityController<SettingsActivity> {
        val intent =
            Intent(
                Intent.ACTION_APPLICATION_PREFERENCES,
            ).setComponent(ComponentName(context, SettingsActivity::class.java))
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        idle()
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(BackupTags.ROW))
        compose.onNodeWithTag(BackupTags.ROW).performClick()
        idle()
        return controller
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun preview(token: String = "t1") =
        RestorePreview(
            token = token,
            counts =
                BackupCounts(
                    settings = 17,
                    favorites = 5,
                    hidden = 2,
                    renames = 2,
                    iconOverrides = 1,
                    themes = 3,
                    widgets = 1,
                ),
            missingApps = listOf("com.example.missing"),
            widgetsToReAdd = 1,
            newerSettingsIgnored = 3,
            droppedEntries = 0,
            excludedThemes = listOf("Broken"),
            needsUpdateThemes = emptyList(),
            workSkipped = 0,
            createdAt = "2026-10-29T10:00:00Z",
        )

    /** Answers the started SAF request with [uri] (null = cancelled). */
    private fun answer(
        activity: Activity,
        uri: String?,
    ): Intent {
        val started = shadowOf(activity).nextStartedActivityForResult
        val data = uri?.let { Intent().setData(Uri.parse(it)) }
        shadowOf(activity).receiveResult(
            started.intent,
            if (uri ==
                null
            ) {
                Activity.RESULT_CANCELED
            } else {
                Activity.RESULT_OK
            },
            data,
        )
        idle()
        return started.intent
    }

    /** T-BACKUP-03: the sheet shows the app-list privacy warning before SAF opens; the opt-ins reach the export. */
    @Test
    fun tBackup03_exportSheetWarnsBeforeTheSystemPicker() {
        graph()
        val activity = open().get()
        compose.onNodeWithTag(BackupTags.EXPORT).performClick()
        idle()
        compose.onNodeWithTag(BackupTags.WARNING).assertIsDisplayed()
        compose
            .onNodeWithText(
                "This file lists the apps on your phone and your Strata settings. Anyone with the file can see that list. It contains no messages, contacts or passwords.",
            ).assertIsDisplayed()
        assertThat(shadowOf(activity).peekNextStartedActivityForResult()).isNull()
        compose.onNodeWithTag(BackupTags.INCLUDE_LOG).performClick()
        compose.onNodeWithTag(BackupTags.CONFIRM_EXPORT).performClick()
        idle()
        val intent = answer(activity, "content://docs/backup.json")
        assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(intent.type).isEqualTo("application/json")
        assertThat(intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("strata-2026-11-12.strata-backup.json")
        assertThat(service.exports).containsExactly("content://docs/backup.json" to ExportOptions(launchLog = true))
        compose.onNodeWithText("Backup saved.").assertIsDisplayed()
    }

    @Test
    fun cancelledPicker_andStrayResults_doNothing() {
        graph()
        val activity = open().get()
        compose.onNodeWithTag(BackupTags.EXPORT).performClick()
        idle()
        compose.onNodeWithTag(BackupTags.CONFIRM_EXPORT).performClick()
        idle()
        answer(activity, null)
        assertThat(service.exports).isEmpty()
        // A result nobody asked for (e.g. delivered twice) is ignored.
        compose.onNodeWithTag(BackupTags.RESTORE).performClick()
        idle()
        answer(activity, "content://docs/x.json")
        assertThat(service.previews).containsExactly("content://docs/x.json")
        assertThat(service.exports).isEmpty()
    }

    /** T-BACKUP-05/06 (C half): each rejection shows its own message and restores nothing. */
    @Test
    fun tBackup05_06_rejectedFilesShowSpecificMessages() {
        graph()
        val activity = open().get()
        val cases =
            listOf(
                BackupError.NewerVersion to
                    "Made with a newer version of Strata Launcher. Update the app to restore it.",
                BackupError.NotABackup to "This file isn't a Strata backup.",
                BackupError.Damaged(12, 4) to "This backup is damaged (line 12, column 4).",
                BackupError.TooLarge to "This backup is too large.",
            )
        for ((error, text) in cases) {
            service.previewResult = Outcome.Err(error)
            compose.onNodeWithTag(BackupTags.RESTORE).performClick()
            idle()
            answer(activity, "content://docs/bad.json")
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithTag(BackupTags.PREVIEW).assertDoesNotExist()
        }
        assertThat(service.restores).isEmpty()
    }

    /** J-11: OpenDocument → summary → "Replace current setup" → "Undo restore". */
    @Test
    fun restorePreviewReplaceAndUndo() {
        graph()
        service.previewResult = Outcome.Ok(preview())
        val activity = open().get()
        compose.onNodeWithTag(BackupTags.RESTORE).performClick()
        idle()
        val intent = answer(activity, "content://docs/b.json")
        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
            .asList()
            .containsExactly("application/json", "application/octet-stream", "text/plain")
        compose.onNodeWithTag(BackupTags.PREVIEW).assertIsDisplayed()
        compose.onNodeWithText("5 favourites").assertIsDisplayed()
        compose.onNodeWithText("1 widget to re-add").assertIsDisplayed()
        compose.onNodeWithText("3 newer settings will be ignored").assertIsDisplayed()
        compose.onNodeWithText("com.example.missing").assertIsDisplayed()
        compose.onNodeWithText("Not restored (damaged theme): Broken").assertIsDisplayed()
        assertThat(service.restores).isEmpty() // nothing written before Replace
        compose.onNodeWithTag(BackupTags.REPLACE).performClick()
        idle()
        assertThat(service.restores).containsExactly("t1")
        compose.onNodeWithText("Your setup was restored.").assertIsDisplayed()
        compose.onNodeWithTag(BackupTags.UNDO).performClick()
        idle()
        assertThat(service.undos).isEqualTo(1)
        compose.onNodeWithText("The restore was undone.").assertIsDisplayed()
        compose.onNodeWithTag(BackupTags.UNDO).assertDoesNotExist()
    }

    @Test
    fun cancelPreview_discardsThePreparedRestore() {
        graph()
        service.previewResult = Outcome.Ok(preview("t9"))
        val activity = open().get()
        compose.onNodeWithTag(BackupTags.RESTORE).performClick()
        idle()
        answer(activity, "content://docs/b.json")
        compose.onNodeWithTag(BackupTags.CANCEL_PREVIEW).performClick()
        idle()
        assertThat(service.discarded).containsExactly("t9")
        assertThat(service.restores).isEmpty()
    }

    /** The picker result after the activity was recreated is still acted on (pending state in SavedStateHandle). */
    @Test
    fun exportResultAfterRecreation_isStillWritten() {
        graph()
        val controller = open()
        compose.onNodeWithTag(BackupTags.EXPORT).performClick()
        idle()
        compose.onNodeWithTag(BackupTags.CONFIRM_EXPORT).performClick()
        idle()
        val started = shadowOf(controller.get()).nextStartedActivityForResult
        controller.recreate()
        idle()
        shadowOf(controller.get()).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://docs/after.json")),
        )
        idle()
        assertThat(service.exports.map { it.first }).containsExactly("content://docs/after.json")
    }
}
