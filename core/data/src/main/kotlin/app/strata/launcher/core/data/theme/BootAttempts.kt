package app.strata.launcher.core.data.theme

import app.strata.launcher.core.common.Clock
import java.io.File
import java.io.IOException

/**
 * The process-level crash-loop counter of ARCHITECTURE §10: `noBackupFilesDir/boot.state`, a plain file holding
 * `<count> <firstAttemptEpochMs>`. Incremented at every start and deleted once the start is known good
 * (10 s after the first frame). Two failed starts within 5 minutes put the next start into safe mode. It is
 * checked before the theme guard of THEME_SYSTEM §12 step 4. Blocking: call off the main thread.
 */
class BootAttempts(
    private val file: () -> File,
    private val clock: Clock,
) {
    /** Records this start. Returns true when it must run in safe mode. */
    fun onStart(): Boolean {
        val now = clock.nowMillis()
        val (count, firstAt) = read() ?: (0 to now)
        val inWindow = count > 0 && now - firstAt in 0..WINDOW_MS
        val failed = if (inWindow) count else 0
        write(failed + 1, if (inWindow) firstAt else now)
        return failed >= FAILED_STARTS_FOR_SAFE_MODE
    }

    /** The start is known good: forget the attempts. */
    fun onSucceeded() {
        runCatching { file().delete() }
    }

    private fun read(): Pair<Int, Long>? =
        try {
            val parts =
                file()
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
                    ?.split(' ') ?: return null
            val count = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
            if (count < 0) null else count to at
        } catch (e: IOException) {
            null
        }

    private fun write(
        count: Int,
        firstAt: Long,
    ) {
        try {
            val f = file()
            f.parentFile?.mkdirs()
            // A torn write reads as "no attempts", which errs towards a normal start.
            f.writeText("$count $firstAt")
        } catch (e: IOException) {
            // Without the counter the start is simply not guarded.
        }
    }

    companion object {
        const val FILE_NAME = "boot.state"
        const val WINDOW_MS = 5 * 60 * 1000L
        const val FAILED_STARTS_FOR_SAFE_MODE = 2
    }
}
