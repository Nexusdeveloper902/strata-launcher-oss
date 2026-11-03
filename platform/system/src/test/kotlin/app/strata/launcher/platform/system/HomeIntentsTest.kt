package app.strata.launcher.platform.system

import android.app.Application
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.apps.LaunchError
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Header and settings hand-offs of [AndroidSystemIntents] (FR-HOME-03, FR-GEST-02/03), Robolectric. */
@RunWith(AndroidJUnit4::class)
class HomeIntentsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val intents = AndroidSystemIntents(app)

    /** T-HOME-05 (R half): the clock starts `ACTION_SHOW_ALARMS`; the date the calendar time URI. */
    @Test
    fun clockAndCalendarIntents() {
        assertThat(intents.showAlarms()).isEqualTo(Outcome.Ok(Unit))
        assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo(AlarmClock.ACTION_SHOW_ALARMS)
        assertThat(intents.openCalendar(1234L)).isEqualTo(Outcome.Ok(Unit))
        val calendar = shadowOf(app).nextStartedActivity
        assertThat(calendar.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(calendar.data.toString()).isEqualTo("content://com.android.calendar/time/1234")
        assertThat(intents.accessibilitySettings()).isEqualTo(Outcome.Ok(Unit))
        assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /** T-HOME-06 (R half): no handler gives NOT_FOUND instead of an exception. */
    @Test
    fun noHandlerIsNotFound() {
        shadowOf(app).checkActivities(true)
        assertThat(intents.showAlarms()).isEqualTo(Outcome.Err(LaunchError.NOT_FOUND))
        assertThat(intents.openCalendar(0L)).isEqualTo(Outcome.Err(LaunchError.NOT_FOUND))
    }

    /** FR-GEST-03: below API 28 the lock is unsupported (the setting is absent). */
    @Test
    @Config(sdk = [27])
    fun lockUnsupportedBelow28() {
        val lock = AndroidLockService(app)
        assertThat(lock.supported).isFalse()
        assertThat(lock.enabled.value).isFalse()
        assertThat(lock.lock()).isFalse()
    }

    /** Not connected → no lock and no crash (T-GEST-04 groundwork; the device half needs the real service). */
    @Test
    fun lockWithoutServiceFails() {
        val lock = AndroidLockService(app)
        assertThat(lock.supported).isTrue()
        assertThat(lock.lock()).isFalse()
    }
}
