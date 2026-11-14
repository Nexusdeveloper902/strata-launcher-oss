package app.strata.launcher.platform.apps

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.cancellation.CancellationException

/**
 * A binder start call can rethrow anything `system_server` threw: on API 26 `LauncherApps.startMainActivity` of a
 * component that is gone comes back as a `NullPointerException` (GMD API 26). It must become a typed launch failure,
 * logged, never a crash (FR-APPS-02, ARCHITECTURE §11).
 */
@RunWith(AndroidJUnit4::class)
class LaunchGuardTest {
    private val logger = RecordingLogger()

    @Test
    fun npeFromTheBinderIsAnUnknownLaunchFailure_andLogged() {
        val r =
            guardLaunch(logger, "Launch", "launch", { false }) { throw NullPointerException("ComponentInfo.exported") }
        assertThat(r).isEqualTo(Outcome.Err(LaunchError.UNKNOWN))
        assertThat(logger.entries.single().message).isEqualTo("launch failed: UNKNOWN")
        assertThat(logger.entries.single().throwable).isInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun documentedExceptionsKeepTheirMapping_andPrivateFailuresLogNoException() {
        assertThat(
            guardLaunch(logger, "T", "x", {
                false
            }) { throw SecurityException() },
        ).isEqualTo(Outcome.Err(LaunchError.SECURITY))
        assertThat(guardLaunch(logger, "T", "x", { false }) { throw IllegalArgumentException() })
            .isEqualTo(Outcome.Err(LaunchError.NOT_FOUND))
        assertThat(
            guardLaunch(logger, "T", "x", {
                false
            }, illegalState = LaunchError.NOT_FOUND) { throw IllegalStateException() },
        ).isEqualTo(Outcome.Err(LaunchError.NOT_FOUND))
        logger.entries.clear()
        guardLaunch(logger, "T", "x", { true }) { throw NullPointerException("private component") }
        assertThat(logger.entries.single().throwable).isNull()
    }

    @Test
    fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            guardLaunch(logger, "T", "x", { false }) { throw CancellationException("stop") }
        }
    }

    /** Through the real adapter: a start call that throws an NPE gives a typed failure instead of crashing. */
    @Test
    fun launcherMapsAnNpeFromItsStartCall() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val throwing =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun startActivity(intent: Intent?) = throw NullPointerException("ComponentInfo.exported")
            }
        val launcher = LauncherAppsAppLauncher(throwing, logger)
        val r = launcher.requestUninstall(AppKey("fixture.maps/fixture.maps.MainActivity", 0))
        assertThat(r).isEqualTo(Outcome.Err(LaunchError.UNKNOWN))
        assertThat(logger.entries.map { it.message }).containsExactly("uninstall failed: UNKNOWN")
    }
}
