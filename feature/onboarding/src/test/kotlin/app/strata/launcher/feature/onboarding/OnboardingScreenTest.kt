package app.strata.launcher.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S-ONB screens (Robolectric Compose, level C). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val section = onboardingApps(14)
    private val favorites = FakeFavoritesRepository()
    private val settings = FakeSettingsRepository(Settings(onboardingDone = false))
    private val role = FakeRoleController(defaultHome = false)
    private var finished = 0

    private fun show(): OnboardingViewModel {
        val vm =
            OnboardingViewModel(
                FakeAppListSource(AppList(listOf(section), emptyList(), hiddenCount = 0, fromSnapshot = false)),
                favorites,
                settings,
                role,
                Dispatchers.Unconfined,
                RecordingLogger(),
                SavedStateHandle(),
            )
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            OnboardingRoute(state, vm.effects, vm::onAction, onFinished = { finished++ }, onLeave = null)
        }
        return vm
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performClick()

    private fun pick(i: Int) {
        val tag = OnboardingTestTags.pick(section.apps[i].key)
        compose.onNodeWithTag(OnboardingTestTags.FAVORITES).performScrollToNode(hasTestTag(tag))
        tap(tag)
    }

    /** T-ONBOARD-02: nothing preselected; pick 3; the limit of 12 is enforced on screen; Continue stores the picks. */
    @Test
    fun tOnboard02_pickThree_thenLimit() {
        show()
        tap(OnboardingTestTags.CONTINUE)
        compose.onNodeWithTag(OnboardingTestTags.FAVORITES).assertExists()
        section.apps.indices.forEach { i ->
            val tag = OnboardingTestTags.pick(section.apps[i].key)
            compose.onNodeWithTag(OnboardingTestTags.FAVORITES).performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsOff()
        }
        listOf(2, 5, 7).forEach(::pick)
        compose.onNodeWithTag(OnboardingTestTags.pick(section.apps[5].key)).assertIsOn()
        (0 until 14).filter { it !in listOf(2, 5, 7) }.forEach(::pick) // 11 more: only 9 fit
        compose.onNodeWithTag(OnboardingTestTags.FAVORITES_LIMIT).assertExists()
        tap(OnboardingTestTags.CONTINUE)
        assertThat(favorites.state.value).hasSize(12)
        assertThat(favorites.state.value.take(3))
            .containsExactly(
                section.apps[2].key,
                section.apps[5].key,
                section.apps[7].key,
            ).inOrder()
        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT_STEP).assertExists()
    }

    /** T-ONBOARD-02: Skip leaves no favourite (home keeps "Add favourites"). */
    @Test
    fun tOnboard02_skip() {
        show()
        tap(OnboardingTestTags.CONTINUE)
        pick(0)
        tap(OnboardingTestTags.SKIP)
        assertThat(favorites.state.value).isEmpty()
        compose.onNodeWithTag(OnboardingTestTags.SET_DEFAULT).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.NOT_NOW).assertExists()
    }

    /** T-ONBOARD-01 (C half): ≤ 4 screens without changes, no permission requested; Not now ends on home. */
    @Test
    fun tOnboard01_walkWithoutChanges() {
        show()
        compose.onNodeWithTag(OnboardingTestTags.WELCOME).assertExists()
        tap(OnboardingTestTags.CONTINUE)
        tap(OnboardingTestTags.SKIP)
        tap(OnboardingTestTags.NOT_NOW)
        compose.waitForIdle()
        assertThat(settings.value.onboardingDone).isTrue()
        assertThat(finished).isEqualTo(1)
    }

    /** T-ONBOARD-07 (C half): the OEM help card with Retry, and the HyperOS note for a HyperOS 2 build. */
    @Test
    fun tOnboard07_oemHelpCard() {
        role.device = SetDefaultFlowTest.HYPER_OS_2
        role.request = SystemIntent.HomeSettings
        val vm = show()
        tap(OnboardingTestTags.CONTINUE)
        tap(OnboardingTestTags.SKIP)
        tap(OnboardingTestTags.SET_DEFAULT)
        vm.setDefault.onReturned(SetDefaultAttempt.SETTINGS, ok = false, openMillis = 5_000)
        compose.onNodeWithTag(OnboardingTestTags.OEM_HELP).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.OEM_BLOCKING_NOTE).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.RETRY).assertExists()
    }

    /** J-01: the Denied card offers Try again, Open settings and Not now. */
    @Test
    fun deniedCard() {
        val vm = show()
        tap(OnboardingTestTags.CONTINUE)
        tap(OnboardingTestTags.SKIP)
        tap(OnboardingTestTags.SET_DEFAULT)
        vm.setDefault.onReturned(SetDefaultAttempt.ROLE, ok = false, openMillis = 5_000)
        compose.onNodeWithTag(OnboardingTestTags.DENIED_CARD).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.TRY_AGAIN).assertExists()
        compose.onNodeWithTag(OnboardingTestTags.OPEN_SETTINGS).assertExists()
        tap(OnboardingTestTags.CARD_NOT_NOW)
        compose.waitForIdle()
        assertThat(settings.value.onboardingDone).isTrue()
    }
}
