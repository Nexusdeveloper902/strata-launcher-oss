package app.strata.launcher.di

import android.content.Context
import app.strata.launcher.BuildConfig
import app.strata.launcher.core.common.AppDispatchers
import app.strata.launcher.core.common.log.LogLevel
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.common.log.RingLogger
import app.strata.launcher.core.common.log.RotatingFileLogSink
import app.strata.launcher.core.data.onboarding.DataStoreOnboardingStore
import app.strata.launcher.core.platformapi.settings.OnboardingStore
import app.strata.launcher.core.platformapi.system.RoleController
import app.strata.launcher.diagnostics.LogcatSink
import app.strata.launcher.platform.system.AndroidRoleController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Production bindings. Everything is lazy: building the graph does no I/O (ARCHITECTURE §10 step 1). */
class DefaultAppGraph(context: Context) : AppGraph {
    private val appContext = context.applicationContext

    override val dispatchers: AppDispatchers = AppDispatchers.standard()

    override val logger: Logger by lazy {
        RingLogger(
            sinks = listOf(
                RotatingFileLogSink(directory = { File(appContext.noBackupFilesDir, "logs") }),
                LogcatSink(minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN),
            ),
        )
    }

    override val appScope: CoroutineScope by lazy {
        val handler = CoroutineExceptionHandler { _, t -> logger.e(TAG, "Uncaught coroutine failure", t) }
        CoroutineScope(SupervisorJob() + dispatchers.default + handler)
    }

    override val roleController: RoleController by lazy { AndroidRoleController(appContext) }

    override val onboardingStore: OnboardingStore by lazy {
        DataStoreOnboardingStore.create(appContext, CoroutineScope(SupervisorJob() + dispatchers.io))
    }

    private companion object {
        const val TAG = "AppGraph"
    }
}
