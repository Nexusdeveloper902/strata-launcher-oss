package app.strata.launcher.core.data.theme

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.strata.launcher.core.common.Clock
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.theme.store.BuiltInThemeSource
import app.strata.launcher.core.theme.store.ThemeLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.concurrent.thread

/**
 * T-THEME-45 writer, in the `:t45writer` process: saves themes through the real library (AtomicFile files, then
 * the DataStore index) as fast as it can until the test kills the process.
 */
class ThemeWriterService : Service() {
    override fun onBind(intent: Intent): IBinder {
        thread(name = "t45-writer", isDaemon = true) { writeUntilKilled(this) }
        return Binder()
    }

    companion object {
        val IDS =
            listOf(
                "aaaaaaaa-0000-4000-8000-000000000001",
                "bbbbbbbb-0000-4000-8000-000000000002",
                "cccccccc-0000-4000-8000-000000000003",
            )

        fun root(context: Context) = File(context.filesDir, "t45")

        fun library(
            context: Context,
            scope: CoroutineScope,
        ): Pair<AtomicFileThemeFileStore, ThemeLibrary> {
            val files = AtomicFileThemeFileStore({ File(root(context), "themes") })
            val index = DataStoreThemeIndexStore.create(scope) { File(root(context), "theme_index.pb") }
            return files to ThemeLibrary(files, index, NoBuiltIns, Clock.System)
        }

        /** The save generation stored in a theme's name (`G<n>`), or null if the bytes are not a valid theme. */
        fun generation(bytes: ByteArray): Long? {
            val parsed =
                app.strata.launcher.core.theme.parse
                    .ThemeParser()
                    .parse(bytes)
            return (parsed as? Outcome.Ok)
                ?.value
                ?.document
                ?.name
                ?.removePrefix("G")
                ?.toLongOrNull()
        }

        fun tree(
            id: String,
            gen: Long,
        ): JsonObject {
            val accent = String.format(java.util.Locale.ROOT, "#%06X", (gen * 7919L % 0xFFFFFFL).toInt())
            val json = """{"schemaVersion":1,"id":"$id","name":"G$gen","colors":{"light":{"accent":"$accent"}}}"""
            return (JsonParser.parse(json.toByteArray()) as Outcome.Ok).value as JsonObject
        }

        private fun writeUntilKilled(context: Context) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val (files, library) = library(context, scope)
            library.boot()
            var gen =
                files
                    .list(ThemeLibrary.USER_DIR)
                    .mapNotNull { name -> files.read("${ThemeLibrary.USER_DIR}/$name")?.let(::generation) }
                    .maxOrNull()
                    ?.plus(1) ?: 0L
            while (true) {
                library.save(tree(IDS[(gen % IDS.size).toInt()], gen))
                gen++
            }
        }
    }
}

private object NoBuiltIns : BuiltInThemeSource {
    override fun names(): List<String> = emptyList()

    override fun read(name: String): ByteArray? = null
}
