package app.strata.launcher.platform.apps

import android.content.ActivityNotFoundException
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a `LauncherApps` / `startActivity` call and maps every platform exception to a [LaunchError] (ARCHITECTURE
 * §11: nothing platform-typed escapes an adapter). Besides the documented ones, a binder call can rethrow whatever
 * `system_server` threw: on API 26 `startMainActivity` of a component that no longer exists comes back as a
 * `NullPointerException` (GMD API 26), so any other [RuntimeException] is [LaunchError.UNKNOWN] instead of a crash.
 * [CancellationException] is never swallowed. Failures are logged through the (release-redacted) [logger]; for a
 * private-space app ([isPrivate]) only the error kind, without the exception (P-5).
 */
internal inline fun guardLaunch(
    logger: Logger,
    tag: String,
    what: String,
    crossinline isPrivate: () -> Boolean,
    /** What an `IllegalStateException` means here (a shortcut: removed or disabled meanwhile). */
    illegalState: LaunchError = LaunchError.UNKNOWN,
    block: () -> LaunchResult,
): LaunchResult {
    val fail = { error: LaunchError, e: Exception ->
        launchFailed(logger, tag, what, error, e, runCatching { isPrivate() }.getOrDefault(true))
    }
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ActivityNotFoundException) {
        fail(LaunchError.NOT_FOUND, e)
    } catch (e: SecurityException) {
        fail(LaunchError.SECURITY, e)
    } catch (e: IllegalArgumentException) {
        fail(LaunchError.NOT_FOUND, e)
    } catch (e: IllegalStateException) {
        fail(illegalState, e)
    } catch (e: RuntimeException) {
        fail(LaunchError.UNKNOWN, e)
    }
}

/** Logs a launch failure; a private-space failure without its exception, whose message can name the app (P-5). */
@PublishedApi
internal fun launchFailed(
    logger: Logger,
    tag: String,
    what: String,
    error: LaunchError,
    e: Exception,
    private: Boolean,
): LaunchResult {
    logger.w(tag, "$what failed: $error", e.takeUnless { private })
    return Outcome.Err(error)
}
