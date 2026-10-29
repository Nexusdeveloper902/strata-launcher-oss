package app.strata.launcher

import androidx.test.core.app.ApplicationProvider
import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.di.AppGraph
import app.strata.launcher.testing.fakes.FakeOnboardingStore
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** [AppGraph] of fakes (ARCHITECTURE §7: tests swap the graph wholesale). */
class TestAppGraph(
    override val roleController: FakeRoleController = FakeRoleController(),
    override val onboardingStore: FakeOnboardingStore = FakeOnboardingStore(),
    override val logger: RecordingLogger = RecordingLogger(),
) : AppGraph {
    override val dispatchers = AppDispatchers(
        main = Dispatchers.Main,
        io = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
    )
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Installs this graph into the Robolectric application before any activity starts. */
    fun install(): TestAppGraph = apply {
        ApplicationProvider.getApplicationContext<StrataApplication>().graph = this
    }
}
