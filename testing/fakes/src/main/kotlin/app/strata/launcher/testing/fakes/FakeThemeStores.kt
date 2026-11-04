package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.editor.EditorDraft
import app.strata.launcher.core.theme.importing.ConflictChoice
import app.strata.launcher.core.theme.importing.ImportError
import app.strata.launcher.core.theme.importing.ThemeImage
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.ThemeGlobals
import app.strata.launcher.core.theme.store.BuiltInThemeSource
import app.strata.launcher.core.theme.store.DeleteTicket
import app.strata.launcher.core.theme.store.EditTarget
import app.strata.launcher.core.theme.store.EditorSaveError
import app.strata.launcher.core.theme.store.ExportError
import app.strata.launcher.core.theme.store.ExportFile
import app.strata.launcher.core.theme.store.ImageSeedError
import app.strata.launcher.core.theme.store.ImageSeedResult
import app.strata.launcher.core.theme.store.ImportCommit
import app.strata.launcher.core.theme.store.ImportPreview
import app.strata.launcher.core.theme.store.PreviewEnvironment
import app.strata.launcher.core.theme.store.ThemeEditorStore
import app.strata.launcher.core.theme.store.ThemeFileStore
import app.strata.launcher.core.theme.store.ThemeIndex
import app.strata.launcher.core.theme.store.ThemeIndexStore
import app.strata.launcher.core.theme.store.ThemeManager
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.core.theme.store.ThemeSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Thrown by the fakes to simulate the process dying at a chosen operation. */
class SimulatedKill : RuntimeException("simulated process death")

/**
 * In-memory [ThemeFileStore] with the AtomicFile contract (each write is all-or-nothing). Set [killAfterWrites]
 * to n to make the (n+1)-th following write or move throw [SimulatedKill] *before* it takes effect.
 */
class InMemoryThemeFileStore : ThemeFileStore {
    val files = sortedMapOf<String, ByteArray>()
    var killAfterWrites: Int = -1
    val unreadable = mutableSetOf<String>()

    /** Thrown by every following write (e.g. an [IllegalStateException] to simulate a non-I/O bug). */
    var writeFailure: Exception? = null

    private fun tick() {
        if (killAfterWrites == 0) {
            killAfterWrites = -1
            throw SimulatedKill()
        }
        if (killAfterWrites > 0) killAfterWrites--
    }

    override fun read(name: String): ByteArray? {
        if (name in unreadable) throw IOException("unreadable")
        return files[name]?.copyOf()
    }

    override fun readMedia(
        name: String,
        maxBytes: Int,
    ): ByteArray? = read(name)?.let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        writeFailure?.let { throw it }
        tick()
        files[name] = bytes.copyOf()
    }

    override fun delete(name: String): Boolean = files.remove(name) != null

    override fun list(dir: String): List<String> =
        files.keys
            .filter {
                it.startsWith(
                    "$dir/",
                ) && '/' !in it.removePrefix("$dir/")
            }.map { it.removePrefix("$dir/") }

    override fun move(
        from: String,
        to: String,
    ) {
        tick()
        val b = files.remove(from) ?: throw IOException("missing")
        files[to] = b
    }
}

/** In-memory [ThemeIndexStore]; [corrupt] simulates a `CorruptionException` (read returns null). */
class InMemoryThemeIndexStore : ThemeIndexStore {
    var stored: ThemeIndex? = null
    var corrupt = false
    var killOnWrite = false

    override fun read(): ThemeIndex? = if (corrupt) null else stored

    override fun write(index: ThemeIndex) {
        if (killOnWrite) {
            killOnWrite = false
            throw SimulatedKill()
        }
        corrupt = false
        stored = index
    }
}

class MapBuiltInThemeSource(private val files: Map<String, ByteArray>) : BuiltInThemeSource {
    override fun names(): List<String> = files.keys.sorted()

    override fun read(name: String): ByteArray? = files[name]
}

/**
 * [ThemeRepository] fake: [state] is set directly by the test; calls are recorded. [apply] switches to a theme
 * registered in [themes] (resolved pairs), bumping the generation like the real repository.
 */
class FakeThemeRepository(
    initial: ThemeState = ThemeState(loaded = true),
) : ThemeRepository {
    override val state = MutableStateFlow(initial)
    val themes = linkedMapOf<String, ThemePair>()
    var starts = 0
        private set
    val applied = mutableListOf<String>()
    val firstFrames = mutableListOf<Int>()
    var bootSucceeded = 0
        private set
    var stableRenders = 0
        private set

    override fun start() {
        starts++
    }

    override fun apply(id: String) {
        applied += id
        val pair = themes[id] ?: return
        state.update {
            it.copy(
                themes = pair,
                activeId = id,
                banner = null,
                applyGeneration = it.applyGeneration + 1,
                awaitingFirstFrame = true,
            )
        }
    }

    override fun onFirstFrame(generation: Int) {
        firstFrames += generation
        state.update { if (it.applyGeneration == generation) it.copy(awaitingFirstFrame = false) else it }
    }

    override fun onBootSucceeded() {
        bootSucceeded++
    }

    override fun onStableRender() {
        stableRenders++
    }

    var packNoticeDismissals = 0
        private set

    override fun dismissPackNotice() {
        packNoticeDismissals++
        state.update { it.copy(packNotice = null) }
    }

    override fun dismissBanner() {
        state.update { it.copy(banner = null) }
    }

    override suspend fun library(): List<ThemeSummary> =
        listOf(ThemeSummary(StrataDefault.ID, StrataDefault.NAME, builtIn = true, applicable = true)) +
            themes.keys.map { ThemeSummary(it, it, builtIn = false, applicable = true) }
}

/**
 * Inert [ThemeManager] for tests that do not exercise the library (the real one is the theme repository in
 * `:core:data`; feature tests use it over in-memory stores). Lists Strata Default only; every import is rejected.
 */
class FakeThemeManager : ThemeManager {
    override val revision = MutableStateFlow(0)

    override suspend fun entries(): List<ThemeSummary> =
        listOf(ThemeSummary(StrataDefault.ID, StrataDefault.NAME, builtIn = true, applicable = true))

    override suspend fun prepareImport(bytes: ByteArray): Outcome<ImportPreview, ImportError> =
        Outcome.Err(ImportError.NOT_A_THEME)

    override suspend fun commitImport(
        token: String,
        choice: ConflictChoice,
        apply: Boolean,
    ): Outcome<ImportCommit, ImportError> = Outcome.Err(ImportError.UNREADABLE)

    override fun discardImport(token: String) = Unit

    override suspend fun undoReplace(id: String): Boolean = false

    override suspend fun duplicate(id: String): String? = null

    override suspend fun editableCopy(id: String): String? = null

    override suspend fun requestDelete(id: String): DeleteTicket? = null

    override suspend fun undoDelete(ticket: DeleteTicket) = Unit

    override suspend fun commitDelete(ticket: DeleteTicket): Boolean = false

    override suspend fun setHidden(
        id: String,
        hidden: Boolean,
    ) = Unit

    override suspend fun exportJson(id: String): ExportFile? = null

    override suspend fun hasBundleMedia(id: String): Boolean = false

    override suspend fun exportBundle(id: String): Outcome<ExportFile, ExportError> = Outcome.Err(ExportError.NOT_FOUND)

    override suspend fun wallpaperImage(id: String): ThemeImage? = null
}

/**
 * Inert [ThemeEditorStore] for tests that do not exercise the editor (the real one
 * is the theme repository). Nothing opens; drafts are kept in memory.
 */
class FakeThemeEditorStore : ThemeEditorStore {
    val drafts = LinkedHashMap<String, EditorDraft>()

    override suspend fun openForEdit(id: String): EditTarget? = null

    override suspend fun newTheme(name: String): EditTarget =
        EditTarget(
            NEW_ID,
            null,
            StrataDefault.tree
                .with("id", JsonString(NEW_ID))
                .with("name", JsonString(name)),
            saved = null,
        )

    override suspend fun themeTree(id: String): JsonObject? = null

    override suspend fun saveEdited(
        tree: JsonObject,
    ): Outcome<JsonObject, EditorSaveError> =
        Outcome.Ok(tree)

    override suspend fun writeDraft(draft: EditorDraft): Boolean {
        drafts[draft.sessionId] = draft
        return true
    }

    override suspend fun readDraft(sessionId: String): EditorDraft? =
        drafts[sessionId]

    override suspend fun deleteDraft(sessionId: String) {
        drafts.remove(sessionId)
    }

    override suspend fun drafts(): List<EditorDraft> = drafts.values.toList()

    override suspend fun imageSeed(
        bytes: ByteArray,
        keepAsWallpaper: Boolean,
    ): Outcome<ImageSeedResult, ImageSeedError> =
        Outcome.Err(ImageSeedError.UNREADABLE)

    override fun previewEnvironment(): PreviewEnvironment =
        PreviewEnvironment(
            PlatformInputs(apiLevel = 34),
            ThemeGlobals(),
        )

    private companion object {
        const val NEW_ID = "00000000-0000-4000-8000-00000000e417"
    }
}
