package app.strata.launcher

import android.app.Application
import android.os.Build
import app.strata.launcher.di.AppGraph
import app.strata.launcher.di.DefaultAppGraph
import app.strata.launcher.diagnostics.CrashLoggingHandler
import app.strata.launcher.diagnostics.StrictModeConfig
import app.strata.launcher.diagnostics.StrictModeSetup
import app.strata.launcher.diagnostics.StrictPenalty

class StrataApplication : Application() {
    /** Replaced by tests before an activity starts. */
    @Volatile
    lateinit var graph: AppGraph

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && !isRobolectric()) {
            val penalty = if (BuildConfig.STRICT_MODE_DEATH) StrictPenalty.DEATH else StrictPenalty.LOG
            StrictModeSetup.install(StrictModeConfig.forDebug(penalty, Build.VERSION.SDK_INT)) {
                if (::graph.isInitialized) graph.logger else null
            }
        }
        graph = DefaultAppGraph(this)
        CrashLoggingHandler.install { graph.logger }
    }

    private fun isRobolectric(): Boolean = Build.FINGERPRINT == "robolectric"
}

/** The process-wide graph. */
val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as StrataApplication).graph
