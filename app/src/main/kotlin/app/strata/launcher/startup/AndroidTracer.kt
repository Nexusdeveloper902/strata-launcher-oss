package app.strata.launcher.startup

import android.os.Trace
import app.strata.launcher.core.common.trace.Tracer

/** [Tracer] on `android.os.Trace`: free while no trace is being recorded; read by the `:benchmark` Macrobenchmarks. */
object AndroidTracer : Tracer {
    override fun begin(name: String) = Trace.beginSection(name)

    override fun end() = Trace.endSection()
}
