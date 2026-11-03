package app.strata.launcher.platform.apps

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** T-APPS-11 (R half): Uninstall starts the system `ACTION_DELETE` flow for the package (ANDROID_PLATFORM §5). */
@RunWith(AndroidJUnit4::class)
class UninstallTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun tApps11_uninstallStartsActionDelete() {
        val launcher = LauncherAppsAppLauncher(app, RecordingLogger())
        val result = launcher.requestUninstall(AppKey("fixture.maps/fixture.maps.MainActivity", 0))
        assertThat(result).isEqualTo(Outcome.Ok(Unit))
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_DELETE)
        assertThat(started.data.toString()).isEqualTo("package:fixture.maps")
        assertThat(started.hasExtra(Intent.EXTRA_USER)).isFalse() // main profile
    }

    @Test
    fun malformedKeyIsNotFound() {
        val launcher = LauncherAppsAppLauncher(app, RecordingLogger())
        assertThat(launcher.requestUninstall(AppKey("nonsense", 0))).isInstanceOf(Outcome.Err::class.java)
    }
}
