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
import app.strata.launcher.core.model.Settings
import app.strata.launcher.feature.settings.DiagnosticTags
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.testing.fakes.FakeDiagnosticLogService
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** About -> "Save diagnostic log" (ARCHITECTURE §11; C half of T-PRIV-06) and the FR-SET-04 language row. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h5000dp")
class DiagnosticLogSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service =
        FakeDiagnosticLogService(text = "Strata Launcher diagnostic log\n\nW/Icons: icon failed for <id:abc123>\n")

    private fun openAbout(): Activity {
        TestAppGraph(
            roleController = FakeRoleController(defaultHome = true),
            settingsRepository = FakeSettingsRepository(Settings(onboardingDone = true)),
            diagnostics = service,
        ).install()
        val intent =
            Intent(
                Intent.ACTION_APPLICATION_PREFERENCES,
            ).setComponent(ComponentName(context, SettingsActivity::class.java))
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().get()
        idle()
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(SettingsPageTags.ABOUT_ROW))
        compose.onNodeWithTag(SettingsPageTags.ABOUT_ROW).performClick()
        idle()
        compose.onNodeWithTag(SettingsPageTags.ABOUT_PAGE).performScrollToNode(hasTestTag(DiagnosticTags.ROW))
        return activity
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun answer(
        activity: Activity,
        uri: String?,
    ): Intent {
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(
            started.intent,
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(Uri.parse(it)) },
        )
        idle()
        return started.intent
    }

    /** The preview comes before the system "save as" screen; Save writes exactly the previewed text. */
    @Test
    fun previewThenSave() {
        val activity = openAbout()
        compose.onNodeWithTag(DiagnosticTags.ROW).performClick()
        idle()
        compose.onNodeWithTag(DiagnosticTags.PREVIEW_TEXT).assertIsDisplayed()
        compose.onNodeWithText("icon failed for <id:abc123>", substring = true).assertExists()
        assertThat(shadowOf(activity).peekNextStartedActivityForResult()).isNull()
        compose.onNodeWithTag(DiagnosticTags.SAVE).performClick()
        idle()
        val intent = answer(activity, "content://docs/log.txt")
        assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
        assertThat(intent.type).isEqualTo("text/plain")
        assertThat(intent.getStringExtra(Intent.EXTRA_TITLE)).isEqualTo("strata-diagnostic-log.txt")
        assertThat(service.saved).containsExactly("content://docs/log.txt", service.text)
        compose.onNodeWithText("Diagnostic log saved").assertIsDisplayed()
    }

    @Test
    fun cancelledPickerSavesNothing_andAFailureIsReported() {
        val activity = openAbout()
        compose.onNodeWithTag(DiagnosticTags.ROW).performClick()
        idle()
        compose.onNodeWithTag(DiagnosticTags.SAVE).performClick()
        idle()
        answer(activity, null)
        assertThat(service.saved).isEmpty()
        service.failSave = true
        compose.onNodeWithTag(DiagnosticTags.ROW).performClick()
        idle()
        compose.onNodeWithTag(DiagnosticTags.SAVE).performClick()
        idle()
        answer(activity, "content://docs/log.txt")
        compose.onNodeWithText("The diagnostic log couldn't be saved").assertIsDisplayed()
    }

    @Test
    fun aLogThatCannotBeBuiltShowsTheError() {
        service.text = null
        openAbout()
        compose.onNodeWithTag(DiagnosticTags.ROW).performClick()
        idle()
        compose.onNodeWithTag(DiagnosticTags.PREVIEW).assertDoesNotExist()
        compose.onNodeWithText("The diagnostic log couldn't be saved").assertIsDisplayed()
    }

    /** FR-SET-04 below API 33: no system page exists, so no row (Strata follows the system language). */
    @Test
    @Config(sdk = [32])
    fun noLanguageRowBelow33() {
        openAbout()
        compose.onNodeWithTag(DiagnosticTags.LANGUAGE_ROW).assertDoesNotExist()
    }

    /** FR-SET-04 on API 33+: the row opens the system app-language page for Strata. */
    @Test
    @Config(sdk = [34])
    fun languageRowOpensTheSystemAppLanguagePage() {
        val activity = openAbout()
        compose.onNodeWithTag(SettingsPageTags.ABOUT_PAGE).performScrollToNode(hasTestTag(DiagnosticTags.LANGUAGE_ROW))
        compose.onNodeWithTag(DiagnosticTags.LANGUAGE_ROW).performClick()
        idle()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
        assertThat(started.data.toString()).isEqualTo("package:${context.packageName}")
    }

    /** FR-SET-04 (R half of T-SET-05): with a German configuration the settings pages use the German resources. */
    @Test
    @Config(qualifiers = "de-w411dp-h5000dp")
    fun germanConfigurationShowsGermanText() {
        openAbout()
        compose.onNodeWithText("Über Strata").assertIsDisplayed()
        compose.onNodeWithText("Diagnoseprotokoll speichern").assertIsDisplayed()
    }
}
