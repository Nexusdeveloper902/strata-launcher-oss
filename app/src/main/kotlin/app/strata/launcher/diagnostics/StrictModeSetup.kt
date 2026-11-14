package app.strata.launcher.diagnostics

import android.os.Build
import android.os.StrictMode
import android.util.Log
import app.strata.launcher.core.common.log.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** How violations of the debug StrictMode policies are punished. */
enum class StrictPenalty {
    /** Log only (logcat + the local log). Never kills the process. */
    LOG,

    /** Log, and kill the process on any thread-policy violation. Emulator/CI builds only. */
    DEATH,
}

/**
 * What [StrictModeSetup.install] applies. Pure, so the choice is unit-tested without a device.
 *
 * Thread policy: `detectAll()` always. Death is limited to network-on-main by default: our app has
 * no INTERNET permission and OEM framework code never does network on the app's main thread, while
 * it does do disk reads/writes, unbuffered I/O and custom slow calls (Samsung One UI does all three
 * during the first frames), which `penaltyDeath()` turned into a crash at every launch.
 * Full thread-policy death is opt-in ([deathOnAllThreadViolations]) for the emulator smoke tests.
 * VM policy violations always log only: OEM code trips them as well.
 */
data class StrictModeConfig(
    val deathOnAllThreadViolations: Boolean,
    val deathOnNetwork: Boolean,
    val detectUnsafeIntentLaunch: Boolean,
    val reportToLogger: Boolean,
) {
    companion object {
        /** [penalty] comes from `BuildConfig.STRICT_MODE_DEATH` (`-Pstrata.strictModeDeath=true`). */
        fun forDebug(
            penalty: StrictPenalty,
            sdkInt: Int,
        ): StrictModeConfig =
            StrictModeConfig(
                deathOnAllThreadViolations = penalty == StrictPenalty.DEATH,
                deathOnNetwork = true,
                detectUnsafeIntentLaunch = sdkInt >= Build.VERSION_CODES.S,
                reportToLogger = sdkInt >= Build.VERSION_CODES.P,
            )
    }
}

/** Which thread-policy violations kill the process. */
enum class ThreadDeath { NONE, NETWORK_ONLY, ALL }

/** Debug-only StrictMode (ARCHITECTURE §6, NFR-REL-01; penalties: docs/impl-notes/M0.md deviation). */
object StrictModeSetup {
    private const val TAG = "StrictMode"
    private const val MAX_REPORTED = 200

    private val reported = HashSet<String>()
    private val listenerExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "strata-strictmode").apply { isDaemon = true } }
    }

    /** Pure mapping from config to the thread-policy death choice (unit-tested). */
    internal fun threadDeath(config: StrictModeConfig): ThreadDeath =
        when {
            config.deathOnAllThreadViolations -> ThreadDeath.ALL
            config.deathOnNetwork -> ThreadDeath.NETWORK_ONLY
            else -> ThreadDeath.NONE
        }

    internal fun resetReportedForTest() = synchronized(reported) { reported.clear() }

    /**
     * The config of the policies actually installed in this process, null before [install] succeeded (or if it
     * failed). The T-REL-02 campaign test reads it to prove the run has thread-policy death on.
     */
    @Volatile
    var installed: StrictModeConfig? = null
        private set

    /** Never throws: a broken diagnostics hook must not stop the app from starting. */
    fun install(
        config: StrictModeConfig,
        logger: () -> Logger?,
    ) {
        try {
            apply(config, logger)
        } catch (t: Throwable) {
            Log.w(TAG, "StrictMode setup failed; continuing without it", t)
        }
    }

    private fun apply(
        config: StrictModeConfig,
        logger: () -> Logger?,
    ) {
        val thread =
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
        when (threadDeath(config)) {
            ThreadDeath.ALL -> thread.penaltyDeath()
            ThreadDeath.NETWORK_ONLY -> thread.penaltyDeathOnNetwork()
            ThreadDeath.NONE -> Unit
        }
        if (config.reportToLogger && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            thread.penaltyListener(listenerExecutor) { v -> report("thread", v, logger) }
        }
        StrictMode.setThreadPolicy(thread.build())

        val vm =
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectContentUriWithoutPermission()
                .penaltyLog()
        if (config.detectUnsafeIntentLaunch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vm.detectUnsafeIntentLaunch()
        }
        if (config.reportToLogger && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            vm.penaltyListener(listenerExecutor) { v -> report("vm", v, logger) }
        }
        StrictMode.setVmPolicy(vm.build())
        installed = config
    }

    /** Mirrors each distinct violation (by type + top app/framework frame) once into the local log. */
    internal fun report(
        policy: String,
        violation: Throwable,
        logger: () -> Logger?,
    ) {
        runCatching {
            // Resolve the logger first: a violation before it exists must stay unreported so a later one is logged.
            val log = logger() ?: return
            val key = violation.javaClass.name + "@" + violation.stackTrace.take(6).joinToString()
            val first = synchronized(reported) { reported.size < MAX_REPORTED && reported.add(key) }
            if (first) log.w(TAG, "$policy violation ${violation.javaClass.simpleName}", violation)
        }
    }
}
