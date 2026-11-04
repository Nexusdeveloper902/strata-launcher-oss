package app.strata.launcher.feature.theme

import app.strata.launcher.core.data.theme.BootAttempts
import app.strata.launcher.core.data.theme.DefaultThemeRepository
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeDocumentIO
import app.strata.launcher.testing.fakes.FakeSharedFiles
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.InMemoryThemeFileStore
import app.strata.launcher.testing.fakes.InMemoryThemeIndexStore
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.ThemeFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.nio.file.Files

/**
 * The real [DefaultThemeRepository] (library, importer, conflicts) over in-memory stores and the sample built-ins,
 * with every dispatcher unconfined, so view-model and Compose tests see results synchronously.
 */
class ThemeHarness(
    wallpaper: WallpaperColorsInput? = null,
    /** Pass another harness's stores to simulate a new process over the same storage. */
    val files: InMemoryThemeFileStore = InMemoryThemeFileStore(),
    val index: InMemoryThemeIndexStore = InMemoryThemeIndexStore(),
) {
    val clock = FakeClock(1_800_000_000_000)
    val documents = FakeDocumentIO()
    val shared = FakeSharedFiles()
    val intents = FakeSystemIntents()
    val logger = RecordingLogger()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tmp: File = Files.createTempDirectory("themes").toFile()
    val inputs = MutableStateFlow<PlatformInputs?>(PlatformInputs(apiLevel = 34, wallpaperColors = wallpaper))

    val repo =
        DefaultThemeRepository(
            createLibrary = { ThemeLibrary(files, index, MapBuiltInThemeSource(ThemeFixtures.samples()), clock) },
            files = { files },
            bootAttempts = BootAttempts({ File(tmp, "boot.state") }, clock),
            platformInputs = inputs,
            settings = flowOf(Settings()),
            themeDispatcher = Dispatchers.Unconfined,
            resolveDispatcher = Dispatchers.Unconfined,
            scope = scope,
            logger = logger,
            media = ThemeMedia.TRUSTING,
        ).also { it.start() }

    companion object {
        const val PAPER = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"

        fun theme(
            id: String,
            name: String,
            extra: String = "",
        ): ByteArray = """{"schemaVersion":1,"id":"$id","name":"$name"$extra}""".toByteArray()
    }
}
