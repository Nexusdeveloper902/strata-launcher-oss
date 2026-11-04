package app.strata.launcher.theme

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.StrataApplication
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * `:app` halves of the theme file flows (Robolectric): T-SEC-01 (R proxy: non-content URIs refused unread), T-THEME-27
 * (manifest filters: VIEW/SEND for json, octet-stream and zip resolve to ThemeImportActivity, `text/plain` does not),
 * T-SEC-05 (R proxy: the FileProvider is not exported and grants per URI) and the purge of T-THEME-30.
 */
@RunWith(AndroidJUnit4::class)
class ThemeFilesActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<StrataApplication>()

    // ---- T-SEC-01 -------------------------------------------------------------------------------------

    @Test
    fun importSource_acceptsOnlyContentUris() {
        val content = Uri.parse("content://com.example.files/doc/1")
        assertThat(
            ImportSource.of(Intent.ACTION_VIEW, content, null),
        ).isEqualTo(ImportSource.Accepted(content.toString()))
        assertThat(
            ImportSource.of(Intent.ACTION_SEND, null, content),
        ).isEqualTo(ImportSource.Accepted(content.toString()))
        assertThat(ImportSource.of(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/x.json"), null))
            .isEqualTo(ImportSource.Refused)
        assertThat(ImportSource.of(Intent.ACTION_VIEW, Uri.parse("http://example.invalid/x.json"), null))
            .isEqualTo(ImportSource.Refused)
        assertThat(ImportSource.of(Intent.ACTION_SEND, null, Uri.parse("file:///sdcard/x.json")))
            .isEqualTo(ImportSource.Refused)
        assertThat(ImportSource.of(Intent.ACTION_SEND, null, null)).isEqualTo(ImportSource.Missing)
        // A SEND whose data (not stream) is content: is still judged by the stream.
        assertThat(ImportSource.of(Intent.ACTION_SEND, content, null)).isEqualTo(ImportSource.Missing)
    }

    @Test
    fun tSec01_fileUriIsRefusedWithAMessageAndNothingIsRead() {
        val graph = TestAppGraph().install()
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/x.json"))
                .setClass(app, ThemeImportActivity::class.java)
        ActivityScenario.launch<ThemeImportActivity>(intent).use {
            compose.onNodeWithText("Strata can only import files shared by another app.").assertExists()
        }
        assertThat(graph.documents.opened).isEmpty()
    }

    @Test
    fun tSec01_httpStreamIsRefused() {
        val graph = TestAppGraph().install()
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("http://example.invalid/x.json"))
                .setClass(app, ThemeImportActivity::class.java)
        ActivityScenario.launch<ThemeImportActivity>(intent).use {
            compose.onNodeWithText("Strata can only import files shared by another app.").assertExists()
        }
        assertThat(graph.documents.opened).isEmpty()
    }

    // ---- T-THEME-27 (filters) ------------------------------------------------------------------------

    private fun resolvesToImport(intent: Intent): Boolean =
        app.packageManager
            .queryIntentActivities(intent.setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == ThemeImportActivity::class.java.name }

    @Test
    fun tTheme27_viewAndSendFiltersMatchTheThreeTypesOnly() {
        val uri = Uri.parse("content://com.example.files/doc/1")
        for (type in listOf("application/json", "application/octet-stream", "application/zip")) {
            assertThat(resolvesToImport(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type))).isTrue()
            assertThat(resolvesToImport(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)))
                .isTrue()
        }
        assertThat(resolvesToImport(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain"))).isFalse()
        assertThat(resolvesToImport(Intent(Intent.ACTION_SEND).setType("text/plain"))).isFalse()
        // VIEW pins scheme="content".
        assertThat(
            resolvesToImport(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///x.json"), "application/json"),
            ),
        ).isFalse()
    }

    // ---- T-SEC-05 (manifest) -------------------------------------------------------------------------

    @Test
    fun tSec05_fileProviderIsNotExportedAndGrantsPerUri() {
        val info =
            app.packageManager.getProviderInfo(
                ComponentName(app, "androidx.core.content.FileProvider"),
                PackageManager.GET_META_DATA,
            )
        assertThat(info.exported).isFalse()
        assertThat(info.grantUriPermissions).isTrue()
        assertThat(info.authority).isEqualTo("${app.packageName}.files")
        val themes = app.packageManager.getActivityInfo(ComponentName(app, ThemesActivity::class.java), 0)
        assertThat(themes.exported).isFalse()
    }

    // ---- T-THEME-30 (purge) ---------------------------------------------------------------------------

    @Test
    fun tTheme30_shareCopiesAreContentUrisAndPurgedAfter24h() {
        val clock = FakeClock(1_800_000_000_000)
        val dir = File(tmp.root, "share")
        val logger: Logger = RecordingLogger()
        val shared = FileProviderSharedFiles(app, Dispatchers.Unconfined, clock, logger) { dir }
        // FileProvider maps cacheDir/share/; use the real cache dir so the path is inside the configured root.
        val real = FileProviderSharedFiles(app, Dispatchers.Unconfined, clock, logger)
        val uri = runBlocking { real.publish("My theme.strata-theme.json", "{}".toByteArray()) }
        assertThat(uri).startsWith("content://${app.packageName}.files/share/")
        assertThat(uri).endsWith("/My%20theme.strata-theme.json")

        runBlocking { shared.publish("a.json", "{}".toByteArray()) }
        val entry = dir.listFiles()!!.single()
        entry.walkTopDown().forEach { it.setLastModified(clock.now) }
        clock.advanceBy(23 * HOUR)
        runBlocking { shared.purgeExpired() }
        assertThat(dir.listFiles()!!.toList()).hasSize(1)
        clock.advanceBy(2 * HOUR) // 25 h
        runBlocking { shared.purgeExpired() }
        assertThat(dir.listFiles()!!.toList()).isEmpty()
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
