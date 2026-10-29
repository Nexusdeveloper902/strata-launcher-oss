package app.strata.launcher.settings

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** T-HOME-13 (routing table, unit level): D-008 entry routing. */
class EntryRouterTest {
    private val launcher = setOf(Intent.CATEGORY_LAUNCHER)

    @Test
    fun tHome13_launcherIcon_notOnboarded_opensOnboarding() {
        val kind = EntryRouter.classify(Intent.ACTION_MAIN, launcher)
        assertThat(kind).isEqualTo(EntryKind.LAUNCHER_ICON)
        assertThat(EntryRouter.route(kind, onboardingDone = false)).isEqualTo(EntryRoute.ONBOARDING)
    }

    @Test
    fun tHome13_launcherIcon_onboarded_opensHome() {
        val kind = EntryRouter.classify(Intent.ACTION_MAIN, launcher)
        assertThat(EntryRouter.route(kind, onboardingDone = true)).isEqualTo(EntryRoute.HOME)
    }

    @Test
    fun tHome13_applicationPreferences_opensSettingsRootWithoutRouting() {
        val kind = EntryRouter.classify(Intent.ACTION_APPLICATION_PREFERENCES, setOf(Intent.CATEGORY_DEFAULT))
        assertThat(kind).isEqualTo(EntryKind.SETTINGS)
        assertThat(EntryRouter.route(kind, onboardingDone = false)).isEqualTo(EntryRoute.SETTINGS_ROOT)
        assertThat(EntryRouter.route(kind, onboardingDone = true)).isEqualTo(EntryRoute.SETTINGS_ROOT)
    }

    @Test
    fun mainWithoutLauncherCategory_isNotTheIconEntry() {
        assertThat(EntryRouter.classify(Intent.ACTION_MAIN, null)).isEqualTo(EntryKind.SETTINGS)
        assertThat(EntryRouter.classify(null, launcher)).isEqualTo(EntryKind.SETTINGS)
    }
}
