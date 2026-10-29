package app.strata.launcher

import android.app.Application
import android.os.Build
import app.strata.launcher.di.AppGraph
import app.strata.launcher.di.DefaultAppGraph
import app.strata.launcher.diagnostics.CrashLoggingHandler
import app.strata.launcher.diagnostics.StrictModeSetup

class StrataApplication : Application() {
    /** Replaced by tests before an activity starts. */
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && !isRobolectric()) StrictModeSetup.install()
        graph = DefaultAppGraph(this)
        CrashLoggingHandler.install { graph.logger }
    }

    private fun isRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"
}

/** The process-wide graph. */
val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as StrataApplication).graph
