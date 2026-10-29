package app.strata.launcher.diagnostics

import android.os.Build
import android.os.StrictMode

/**
 * Debug-only StrictMode (ARCHITECTURE §6, NFR-REL-01): every thread-policy violation kills the
 * process; the VM policy watches leaked closables, unsafe intent launches (31+) and content URIs
 * sent without a grant.
 */
object StrictModeSetup {
    fun install() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectContentUriWithoutPermission()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vm.detectUnsafeIntentLaunch()
        StrictMode.setVmPolicy(vm.build())
    }
}
