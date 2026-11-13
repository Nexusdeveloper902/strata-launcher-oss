package app.strata.launcher.core.data.rel

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.room.Room
import app.strata.launcher.core.data.LazyResource
import app.strata.launcher.core.data.db.StrataDatabase
import app.strata.launcher.core.data.overrides.RoomFavoritesRepository
import app.strata.launcher.core.data.overrides.RoomOverridesRepository
import app.strata.launcher.core.data.settings.DataStoreSettingsRepository
import app.strata.launcher.core.data.theme.ThemeWriterService
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * T-REL-03 writer, in the `:rel03writer` process: repeats one kind of user-data write through the production stores
 * as fast as it can until the test kills the process. Every write is a generation `g`, so the test can tell a
 * complete old or new state from a mixed one. Files live under `filesDir/rel03/` (never the app's own data).
 */
class StoreWriterService : Service() {
    override fun onBind(intent: Intent): IBinder {
        val kind = Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.REORDER.name)
        thread(name = "rel03-writer", isDaemon = true) { writeUntilKilled(this, kind) }
        return Binder()
    }

    enum class Kind { REORDER, RENAME, SETTINGS, THEME }

    companion object {
        const val EXTRA_KIND = "kind"
        const val FAVORITES = 10
        val KEYS = List(FAVORITES) { AppKey("fixture.rel$it/fixture.rel$it.Main", 0L) }
        val RENAMED = AppKey("fixture.rename/fixture.rename.Main", 0L)

        fun root(context: Context) = File(context.filesDir, "rel03")

        fun database(context: Context): StrataDatabase =
            Room
                .databaseBuilder(
                    context,
                    StrataDatabase::class.java,
                    File(root(context), "rel03.db").absolutePath,
                ).build()

        fun settings(
            context: Context,
            scope: CoroutineScope,
            notices: ResetNotices,
        ) = DataStoreSettingsRepository.create(
            scope = scope,
            resetNotices = notices,
            legacyFlags = { File(root(context), "none.bin") },
            produceFile = { File(root(context), "settings.pb") },
        )

        /** Generation [g] of the favourites: the keys rotated by g. */
        fun order(g: Long): List<AppKey> = List(FAVORITES) { KEYS[((it + g) % FAVORITES).toInt()] }

        private fun writeUntilKilled(
            context: Context,
            kind: Kind,
        ) = runBlocking {
            root(context).mkdirs()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            when (kind) {
                Kind.REORDER -> {
                    val db = LazyResource(Dispatchers.IO) { database(context) }
                    val favorites = RoomFavoritesRepository(db)
                    for (k in KEYS) if (k !in favorites.favorites.first()) favorites.add(k) { true }
                    var g = 0L
                    while (true) favorites.reorder(order(g++))
                }

                Kind.RENAME -> {
                    val overrides = RoomOverridesRepository(LazyResource(Dispatchers.IO) { database(context) })
                    var g =
                        overrides.overrides
                            .first()[RENAMED]
                            ?.customLabel
                            ?.removePrefix("G")
                            ?.toLongOrNull()
                            ?.plus(1) ?: 0L
                    while (true) overrides.setCustomLabel(RENAMED, ProfileKind.MAIN, "G${g++}")
                }

                Kind.SETTINGS -> {
                    val repo = settings(context, scope, NoNotices)
                    var g = 0L
                    while (true) {
                        val on = g++ % 2 == 0L
                        // Three fields change together in one update: a mixed read is a torn write.
                        repo.update { it.copy(hapticsEnabled = on, listTwoColumn = on, hideStatusBar = on) }
                    }
                }

                Kind.THEME -> {
                    val (files, library) = ThemeWriterService.library(context, scope)
                    library.boot()
                    var g =
                        files
                            .list(app.strata.launcher.core.theme.store.ThemeLibrary.USER_DIR)
                            .mapNotNull { n ->
                                files.read("${app.strata.launcher.core.theme.store.ThemeLibrary.USER_DIR}/$n")
                            }.mapNotNull(ThemeWriterService::generation)
                            .maxOrNull()
                            ?.plus(1) ?: 0L
                    while (true) {
                        library.save(ThemeWriterService.tree(ThemeWriterService.IDS[0], g++))
                    }
                }
            }
        }
    }
}

/** No banner is shown in the writer; the verifier counts resets itself. */
private object NoNotices : ResetNotices {
    override val causes: StateFlow<Set<ResetCause>> = MutableStateFlow(emptySet())

    override fun report(cause: ResetCause) = Unit

    override fun dismiss() = Unit
}
