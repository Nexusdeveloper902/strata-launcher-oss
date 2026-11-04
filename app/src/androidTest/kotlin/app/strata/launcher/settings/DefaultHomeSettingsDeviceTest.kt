package app.strata.launcher.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * T-SET-03 (level I): the "Default home" row and "Uninstall Strata". UiAutomator over the settings test tags (resource
 * ids). The uninstall confirmation is cancelled with Back, so nothing is uninstalled.
 */
@RunWith(AndroidJUnit4::class)
class DefaultHomeSettingsDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun otherHome(): ComponentName? =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .firstOrNull { it.packageName != context.packageName && it.packageName != "com.android.settings" }

    @Test
    fun tSet03_defaultHomeRow_uninstallGuidance() {
        assumeTrue(
            context.applicationContext.appGraph.roleController
                .isDefaultHome(),
        )
        val other = otherHome()
        assumeTrue(other != null)
        other!!
        context.startActivity(
            Intent(Intent.ACTION_APPLICATION_PREFERENCES)
                .setComponent(ComponentName(context, SettingsActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertThat(device.wait(Until.hasObject(By.res(SettingsTestTags.DEFAULT_HOME)), 10_000)).isTrue()
        assertThat(device.wait(Until.hasObject(By.text("Strata is your home app")), 5_000)).isTrue()

        device.findObject(By.res(SettingsTestTags.UNINSTALL_STRATA)).click()
        assertThat(device.wait(Until.hasObject(By.text("Open home settings")), 5_000)).isTrue()
        device.findObject(By.text("Open home settings")).click()
        assertThat(device.wait({ it.currentPackageName != context.packageName }, 10_000)).isTrue()

        // The user switches to another home app, then comes back.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shell("cmd role add-role-holder android.app.role.HOME ${other.packageName}")
        } else {
            shell("cmd package set-home-activity ${other.flattenToString()}")
        }
        device.pressBack()
        val installer = Pattern.compile(".*packageinstaller.*")
        assertThat(device.wait(Until.hasObject(By.pkg(installer)), 10_000)).isTrue()
        device.pressBack() // cancel: Strata stays installed
        assertThat(shell("pm path ${context.packageName}")).contains("package:")
    }
}
