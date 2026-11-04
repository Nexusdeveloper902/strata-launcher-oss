package app.strata.launcher.feature.onboarding

import app.cash.turbine.test
import app.strata.launcher.core.platformapi.system.DeviceBuild
import app.strata.launcher.core.platformapi.system.SystemIntent
import app.strata.launcher.testing.fakes.FakeRoleController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** FR-ONBOARD-02/03 decision logic (level U; the I halves drive the real dialogs on devices). */
@OptIn(ExperimentalCoroutinesApi::class)
class SetDefaultFlowTest {
    private val role = FakeRoleController(defaultHome = false)
    private val flow =
        SetDefaultFlow(
            role,
            Dispatchers.Unconfined,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private val settingsChain =
        listOf(SystemIntent.HomeSettings, SystemIntent.DefaultAppsSettings, SystemIntent.Settings)

    /** T-ONBOARD-03 (logic): RESULT_OK and the role held → granted (onboarding then shows Done). */
    @Test
    fun tOnboard03_roleGranted() =
        runTest {
            flow.requests.test {
                flow.setAsDefault()
                assertThat(
                    awaitItem(),
                ).isEqualTo(SetDefaultLaunch(SetDefaultAttempt.ROLE, listOf(SystemIntent.RequestHomeRole)))
                assertThat(flow.state.value.waiting).isTrue()
                role.defaultHome = true
                flow.onReturned(SetDefaultAttempt.ROLE, ok = true, openMillis = 4_000)
                val s = flow.state.value
                assertThat(s.held).isTrue()
                assertThat(s.grantedByAttempt).isTrue()
                assertThat(s.card).isEqualTo(SetDefaultCard.None)
                assertThat(s.waiting).isFalse()
            }
        }

    /** T-ONBOARD-04 (logic): on 26–28 the first screen is HOME_SETTINGS; the return is checked, never assumed. */
    @Test
    fun tOnboard04_api26to28_settingsPath() =
        runTest {
            role.request = SystemIntent.HomeSettings
            flow.requests.test {
                flow.setAsDefault()
                assertThat(awaitItem()).isEqualTo(SetDefaultLaunch(SetDefaultAttempt.SETTINGS, settingsChain))
                flow.onReturned(SetDefaultAttempt.SETTINGS, ok = false, openMillis = 9_000)
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.OemHelp(knownBlocking = false))
                role.defaultHome = true
                flow.setAsDefault()
                awaitItem()
                flow.onReturned(SetDefaultAttempt.SETTINGS, ok = false, openMillis = 9_000)
                assertThat(flow.state.value.held).isTrue()
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.None)
            }
        }

    /** J-01 step 4: a refusal after the dialog was shown → the Denied card ([Try again] [Open settings] [Not now]). */
    @Test
    fun deniedShowsCard() =
        runTest {
            flow.requests.test {
                flow.setAsDefault()
                awaitItem()
                flow.onReturned(SetDefaultAttempt.ROLE, ok = false, openMillis = 6_000)
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.Denied)
                assertThat(flow.state.value.dontAskAgain).isFalse()
                flow.openSettings()
                assertThat(awaitItem()).isEqualTo(SetDefaultLaunch(SetDefaultAttempt.SETTINGS, settingsChain))
            }
        }

    /**
     * T-ONBOARD-05 (logic): after "don't ask again" the role request comes back RESULT_CANCELED within 2 s without UI.
     * "Try again" then opens settings directly, and every later attempt goes to settings: no silent loop.
     */
    @Test
    fun tOnboard05_dontAskAgain_goesToSettings() =
        runTest {
            flow.requests.test {
                flow.setAsDefault()
                awaitItem()
                flow.onReturned(SetDefaultAttempt.ROLE, ok = false, openMillis = 5_000) // first denial, with UI
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.Denied)
                flow.setAsDefault() // "Try again"
                assertThat(awaitItem().attempt).isEqualTo(SetDefaultAttempt.ROLE)
                flow.onReturned(SetDefaultAttempt.ROLE, ok = false, openMillis = 150) // refused at once, no UI
                assertThat(awaitItem()).isEqualTo(SetDefaultLaunch(SetDefaultAttempt.SETTINGS, settingsChain))
                assertThat(flow.state.value.dontAskAgain).isTrue()
                flow.onReturned(SetDefaultAttempt.SETTINGS, ok = false, openMillis = 3_000)
                flow.setAsDefault() // "Retry"
                assertThat(awaitItem().attempt).isEqualTo(SetDefaultAttempt.SETTINGS)
                expectNoEvents()
            }
        }

    /**
     * T-ONBOARD-07 (logic): RESULT_OK but the role is not held, and a settings return without a change, each show the OEM
     * help card once per attempt with "Retry"; the HyperOS note only for a HyperOS 2 build.
     */
    @Test
    fun tOnboard07_oemHelpOncePerAttempt() =
        runTest {
            flow.requests.test {
                flow.setAsDefault()
                awaitItem()
                flow.onReturned(SetDefaultAttempt.ROLE, ok = true, openMillis = 3_000)
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.OemHelp(knownBlocking = false))
                flow.dismissCard()
                flow.refresh() // a resume does not bring the card back
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.None)

                role.device = HYPER_OS_2
                flow.openSettings()
                awaitItem()
                flow.onReturned(SetDefaultAttempt.SETTINGS, ok = false, openMillis = 8_000)
                assertThat(flow.state.value.card).isEqualTo(SetDefaultCard.OemHelp(knownBlocking = true))
            }
        }

    /** Every settings intent is wrapped: no role activity → settings chain; no settings activity at all → OEM help. */
    @Test
    fun missingActivitiesFallThrough() =
        runTest {
            flow.requests.test {
                flow.setAsDefault()
                awaitItem()
                flow.onNoActivity(SetDefaultAttempt.ROLE)
                assertThat(awaitItem()).isEqualTo(SetDefaultLaunch(SetDefaultAttempt.SETTINGS, settingsChain))
                flow.onNoActivity(SetDefaultAttempt.SETTINGS)
                assertThat(flow.state.value.card).isInstanceOf(SetDefaultCard.OemHelp::class.java)
                assertThat(flow.state.value.waiting).isFalse()
            }
        }

    @Test
    fun hyperOsDetection() {
        assertThat(OemDetector.knownBlocking(HYPER_OS_2)).isTrue()
        assertThat(OemDetector.hyperOsMajor(HYPER_OS_2)).isEqualTo(2)
        val hyperOs1 =
            DeviceBuild(
                "Xiaomi",
                "Redmi",
                "Redmi/x/x:14/UKQ1.231003.002/OS1.0.4.0.UMQEUXM:user/release-keys",
                "OS1.0.4.0.UMQEUXM",
            )
        assertThat(OemDetector.knownBlocking(hyperOs1)).isFalse()
        val miui =
            DeviceBuild(
                "Xiaomi",
                "Xiaomi",
                "Xiaomi/x/x:13/TKQ1.221114.001/V14.0.6.0.TLCMIXM:user/release-keys",
                "V14.0.6.0.TLCMIXM",
            )
        assertThat(OemDetector.hyperOsMajor(miui)).isNull()
        val samsung =
            DeviceBuild(
                "samsung",
                "samsung",
                "samsung/a56xnseea/a56x:15/AP3A.240905.015.A2/A566BXXU3AYE1:user/release-keys",
                "A566BXXU3AYE1",
            )
        assertThat(OemDetector.knownBlocking(samsung)).isFalse()
        assertThat(OemDetector.knownBlocking(DeviceBuild.UNKNOWN)).isFalse()
    }

    companion object {
        val HYPER_OS_2 =
            DeviceBuild(
                manufacturer = "Xiaomi",
                brand = "POCO",
                fingerprint = "POCO/x/x:15/AQ3A.240912.001/OS2.0.3.0.VOJMIXM:user/release-keys",
                incremental = "OS2.0.3.0.VOJMIXM",
            )
    }
}
