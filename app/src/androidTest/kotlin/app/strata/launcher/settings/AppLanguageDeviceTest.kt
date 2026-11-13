package app.strata.launcher.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.focusReport
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-SET-05 (I, API 33+), FR-SET-04: with Strata's app language set to German (what the system per-app language page
 * does, through `LocaleManager`), Settings shows German text while the system language stays as it was. Below API 33
 * there is no per-app language; Strata follows the system language (the test is skipped there).
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var previous: LocaleList? = null

    @Before
    fun before() {
        assumeTrue("per-app language needs API 33", Build.VERSION.SDK_INT >= 33)
        previous = manager().applicationLocales
    }

    @After
    fun after() {
        if (Build.VERSION.SDK_INT < 33) return
        manager().applicationLocales = previous ?: LocaleList.getEmptyLocaleList()
        device.pressBack()
    }

    @RequiresApi(33)
    private fun manager() = context.getSystemService(LocaleManager::class.java)

    @Test
    @RequiresApi(33)
    fun tSet05_germanAppLanguage_systemUnchanged() {
        val system = Resources.getSystem().configuration.locales
        assumeTrue("the system language is already German", system[0].language != "de")
        manager().applicationLocales = LocaleList.forLanguageTags("de")
        assertThat(manager().applicationLocales.toLanguageTags()).isEqualTo("de")
        shell(
            "am start -W -n ${context.packageName}/.settings.SettingsActivity -a android.intent.action.APPLICATION_PREFERENCES",
        )
        assertWithMessage("German settings not shown. ${focusReport()}")
            .that(device.wait(Until.hasObject(By.text("Einstellungen")), 15_000))
            .isTrue()
        // The system language is untouched.
        assertThat(
            Resources
                .getSystem()
                .configuration.locales
                .toLanguageTags(),
        ).isEqualTo(system.toLanguageTags())
        if (Build.VERSION.SDK_INT >= 34) {
            assertThat(manager().systemLocales.toLanguageTags()).isEqualTo(system.toLanguageTags())
        }
    }
}
