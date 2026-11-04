package app.strata.launcher.theme

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Level I theme file flows in the real app: T-THEME-27 (VIEW and SEND with json, octet-stream and zip labels sniff and
 * show the summary first; text/plain does not resolve to Strata), T-SEC-01 (`am start` with file: and http: URIs is
 * refused with a message), T-SEC-05 (the FileProvider refuses a reader without a grant) and T-THEME-30 (a granted
 * receiver reads the share copy; copies older than 24 h are purged at start).
 *
 * The SAF picker itself is system UI and is covered by the manual pass; the picked URI goes the same VIEW path.
 */
@RunWith(AndroidJUnit4::class)
class ThemeFilesDeviceTest {
    @get:Rule
    val device = DeviceStateRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ui: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = context.packageName

    private val json =
        """{"schemaVersion":1,"id":"aaaaaaaa-0000-4000-8000-000000000027","name":"Device import"}""".toByteArray()

    /** A copy behind our own FileProvider; its MIME type comes from the extension. */
    private fun publish(
        name: String,
    ): Uri = Uri.parse(runBlocking { context.appGraph.sharedFiles.publish(name, json) }!!)

    private fun startImport(intent: Intent) {
        context.startActivity(
            intent.setClass(context, ThemeImportActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    private fun waitText(text: String): Boolean = ui.wait(Until.hasObject(By.textContains(text)), TIMEOUT_MS)

    @Test
    fun tTheme27_viewAndSendShowTheSummaryForEveryLabel() {
        val before = context.appGraph.themeRepository.state.value.activeId
        for (name in listOf("a.json", "b.bin", "c.zip")) {
            val uri = publish(name)
            startImport(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri)))
            assertWithMessage("VIEW $name").that(waitText("Device import")).isTrue()
            ui.pressBack()
            startImport(
                Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri),
            )
            assertWithMessage("SEND $name").that(waitText("Device import")).isTrue()
            ui.pressBack()
        }
        // Nothing applied by showing a summary.
        assertThat(context.appGraph.themeRepository.state.value.activeId).isEqualTo(before)
    }

    @Test
    fun tTheme27_textPlainDoesNotResolveToStrata() {
        val uri = Uri.parse("content://com.example.invalid/doc/1")

        fun resolves(i: Intent) =
            context.packageManager
                .queryIntentActivities(i.setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == ThemeImportActivity::class.java.name }
        assertThat(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain"))).isFalse()
        assertThat(resolves(Intent(Intent.ACTION_SEND).setType("text/plain"))).isFalse()
        assertThat(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/json"))).isTrue()
    }

    @Test
    fun tSec01_fileAndHttpUrisAreRefusedWithAMessage() {
        val component = "$pkg/.theme.ThemeImportActivity"
        for (uri in listOf("file:///sdcard/x.json", "http://example.invalid/x.json")) {
            shell("am start -W -a android.intent.action.VIEW -d $uri -t application/json -n $component")
            assertWithMessage(uri).that(waitText("Strata can only import files shared by another app.")).isTrue()
            ui.pressBack()
        }
    }

    @Test
    fun tSec05_providerDeniesAReaderWithoutAGrant() {
        val uri = publish("secret.json")
        // The shell user is another app without a grant. The denial text goes to stderr, which executeShellCommand
        // does not return on every API level (empty on API 26), so the check is that the content never comes back.
        assertThat(shell("content read --uri $uri")).doesNotContain("Device import")
        // Positive control: the same command does read the document once a grant exists.
        context.grantUriPermission("com.android.shell", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            assertThat(shell("content read --uri $uri")).contains("Device import")
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // After the revoke it is denied again.
        assertThat(shell("content read --uri $uri")).doesNotContain("Device import")
    }

    @Test
    fun tTheme30_grantedReceiverReadsAndCopiesArePurged() {
        val uri = publish("shared.strata-theme.json")
        context.grantUriPermission("com.android.shell", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(shell("content read --uri $uri")).contains("Device import")
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val share = File(context.cacheDir, FileProviderSharedFiles.DIR)
        val old = System.currentTimeMillis() - 25 * HOUR
        share.walkTopDown().forEach { it.setLastModified(old) }
        // A fresh process start runs the same purge.
        runBlocking { context.appGraph.sharedFiles.purgeExpired() }
        assertThat(share.listFiles().orEmpty()).isEmpty()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val HOUR = 60 * 60 * 1000L
    }
}
