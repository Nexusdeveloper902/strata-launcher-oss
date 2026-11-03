package app.strata.launcher.diagnostics

import android.os.Build
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.Logger
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Debug StrictMode penalties (NFR-REL-01, docs/impl-notes/M0.md): a real OEM device must not be killed. */
@RunWith(AndroidJUnit4::class)
class StrictModeSetupTest {
    @After
    fun reset() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @Test
    fun logPenaltyDoesNotDieOnAllThreadViolations() {
        val config = StrictModeConfig.forDebug(StrictPenalty.LOG, Build.VERSION_CODES.BAKLAVA)
        assertThat(config.deathOnAllThreadViolations).isFalse()
        assertThat(config.deathOnNetwork).isTrue()
        assertThat(config.reportToLogger).isTrue()
        assertThat(config.detectUnsafeIntentLaunch).isTrue()
    }

    @Test
    fun deathIsOptIn() {
        val config = StrictModeConfig.forDebug(StrictPenalty.DEATH, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertThat(config.deathOnAllThreadViolations).isTrue()
    }

    @Test
    fun oldApiLevelsSkipNewerDetectorsAndListeners() {
        val config = StrictModeConfig.forDebug(StrictPenalty.LOG, Build.VERSION_CODES.O)
        assertThat(config.detectUnsafeIntentLaunch).isFalse()
        assertThat(config.reportToLogger).isFalse()
    }

    @Test
    fun threadDeathIsNetworkOnlyByDefaultAndAllWhenOptedIn() {
        val log = StrictModeConfig.forDebug(StrictPenalty.LOG, Build.VERSION_CODES.BAKLAVA)
        val death = StrictModeConfig.forDebug(StrictPenalty.DEATH, Build.VERSION_CODES.BAKLAVA)
        assertThat(StrictModeSetup.threadDeath(log)).isEqualTo(ThreadDeath.NETWORK_ONLY)
        assertThat(StrictModeSetup.threadDeath(death)).isEqualTo(ThreadDeath.ALL)
        assertThat(StrictModeSetup.threadDeath(log.copy(deathOnNetwork = false))).isEqualTo(ThreadDeath.NONE)
    }

    @Test
    fun violationBeforeLoggerAvailableIsNotConsumed() {
        StrictModeSetup.resetReportedForTest()
        val entries = mutableListOf<String>()
        val logger =
            object : Logger {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                ) {
                    entries += message
                }
            }
        var available: Logger? = null
        val violation = IllegalStateException("violation")
        StrictModeSetup.report("thread", violation) { available }
        assertThat(entries).isEmpty()
        available = logger
        StrictModeSetup.report("thread", violation) { available }
        assertThat(entries).hasSize(1)
        StrictModeSetup.report("thread", violation) { available }
        assertThat(entries).hasSize(1)
        StrictModeSetup.resetReportedForTest()
    }
}
