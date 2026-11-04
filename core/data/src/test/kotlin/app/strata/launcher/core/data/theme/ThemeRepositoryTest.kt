package app.strata.launcher.core.data.theme

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.Variant
import app.strata.launcher.core.theme.resolve.PlatformInputs
import app.strata.launcher.core.theme.resolve.WallpaperColorsInput
import app.strata.launcher.core.theme.store.ThemeBannerKind
import app.strata.launcher.core.theme.store.ThemeLibrary
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * The theme repository over the real Android stores (Robolectric): AtomicFile theme files, the Proto DataStore
 * index and the APK's `assets/themes/`. Each [Process] is one app process over the same files.
 * T-THEME-36 (recovery), T-LIFE-13 (crash-loop safe mode), live switching, T-THEME-05 (R half), T-THEME-39.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ThemeRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = FakeClock(1_800_000_000_000)
    private val themesDir get() = File(tmp.root, "themes")
    private val indexFile get() = File(tmp.root, "datastore/theme_index.pb")
    private val bootFile get() = File(tmp.root, "no_backup/boot.state")
    private val settings = FakeSettingsRepository()
    private val platform = MutableStateFlow<PlatformInputs?>(PlatformInputs(apiLevel = 34))

    private val idA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val idB = "bbbbbbbb-0000-4000-8000-000000000002"
    private val paperId = "c5017d5b-b2b0-48ff-83ed-33f77fd14754"
    private val harborId = "f647effd-96dc-4522-80cf-66af0a8a9dbe"
    private val duskId = "3f4c4574-2d43-49e5-a19f-110a333910ba"

    /** One process: its own DataStore scope (DataStore allows one active instance per file). */
    private inner class Process : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = AtomicFileThemeFileStore({ themesDir })
        val library =
            ThemeLibrary(
                files = files,
                indexStore = DataStoreThemeIndexStore.create(dataStoreScope) { indexFile },
                builtIns = AssetBuiltInThemeSource(context.assets),
                clock = clock,
            )
        val repo =
            DefaultThemeRepository(
                createLibrary = { library },
                files = { files },
                bootAttempts = BootAttempts({ bootFile }, clock),
                platformInputs = platform,
                settings = settings.settings,
                themeDispatcher = Dispatchers.IO.limitedParallelism(1),
                resolveDispatcher = Dispatchers.Default,
                scope = scope,
                logger = RecordingLogger(),
            )

        fun boot(): ThemeState {
            repo.start()
            return await { it.loaded }
        }

        fun await(predicate: (ThemeState) -> Boolean): ThemeState =
            runBlocking { withTimeout(TIMEOUT_MS) { repo.state.first(predicate) } }

        /** Waits until every queued repository call ran (the theme dispatcher is serial and FIFO). */
        fun settle() {
            runBlocking { repo.library() }
        }

        /** The start is good: first frame drawn, 10 s survived, then `onStop`. */
        fun renderStable() {
            val s = repo.state.value
            repo.onFirstFrame(s.applyGeneration)
            repo.onBootSucceeded()
            settle()
            repo.onStableRender()
            settle()
        }

        override fun close() {
            runBlocking {
                scope.coroutineContext.job.cancelAndJoin()
                dataStoreScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    private fun <T> process(block: Process.() -> T): T = Process().use { it.block() }

    private fun theme(
        id: String,
        name: String,
    ): JsonObject =
        (
            JsonParser.parse(
                """{"schemaVersion":1,"id":"$id","name":"$name","colors":{"mode":"dark","dark":{"accent":"#8DB4DE"}}}"""
                    .toByteArray(),
            ) as app.strata.launcher.core.common.Outcome.Ok
        ).value as JsonObject

    /** Stored themes A and B; A became last-known-good, B is active. */
    private fun seedAActiveBActive() {
        process {
            boot()
            runBlocking {
                withContext(Dispatchers.IO) {
                    library.save(theme(idA, "Alpha"))
                    library.save(theme(idB, "Beta"))
                }
            }
            repo.apply(idA)
            await { it.activeId == idA }
            renderStable()
            repo.apply(idB)
            await { it.activeId == idB }
            repo.onFirstFrame(repo.state.value.applyGeneration)
            repo.onBootSucceeded()
            settle()
        }
    }

    private fun corruptIndex() {
        indexFile.writeBytes(byteArrayOf(0x0A, 0x7F, 0x01, 0x02))
    }

    private fun corruptUser(id: String) {
        File(themesDir, "user/$id.strata-theme.json").writeText("{ not json")
    }

    @Test
    fun builtInsAreTheSpecSamples_singleSource() {
        val assets = AssetBuiltInThemeSource(context.assets)
        assertThat(assets.names()).containsExactlyElementsIn(ThemeFixtures.SAMPLE_NAMES).inOrder()
        for (name in ThemeFixtures.SAMPLE_NAMES) assertThat(assets.read(name)).isEqualTo(ThemeFixtures.sample(name))
    }

    @Test
    fun freshInstall_strataDefault_noBanner() {
        val s = process { boot() }
        assertThat(s.activeId).isEqualTo(StrataDefault.ID)
        assertThat(s.banner).isNull()
        assertThat(s.safeMode).isFalse()
        assertThat(s.themes.light).isEqualTo(StrataDefault.resolve(PlatformInputs(apiLevel = 34)))
    }

    /** Live switching: apply re-renders without a restart; the first frame clears the crash-loop flag. */
    @Test
    fun apply_switchesLive_andSurvivesRestart() {
        process {
            boot()
            repo.apply(paperId)
            val s = await { it.activeId == paperId }
            assertThat(s.themes.light.themeId).isEqualTo(paperId)
            assertThat(s.applyGeneration).isEqualTo(1)
            assertThat(s.awaitingFirstFrame).isTrue()
            repo.onFirstFrame(1)
            assertThat(await { !it.awaitingFirstFrame }.activeId).isEqualTo(paperId)
            assertThat(library.currentIndex.applyInProgress).isFalse()
            repo.onBootSucceeded()
            settle()
        }
        val next = process { boot() }
        assertThat(next.activeId).isEqualTo(paperId)
        assertThat(next.banner).isNull()
    }

    @Test
    fun library_listsDefaultThenBuiltInsThenStored() {
        seedAActiveBActive()
        val names = process { boot().let { runBlocking { repo.library() } } }.map { it.name }
        assertThat(names)
            .containsExactly("Strata Default", "Dusk", "Graphite", "Harbor", "Midnight OLED", "Paper", "Alpha", "Beta")
            .inOrder()
    }

    // ---- T-THEME-36 -------------------------------------------------------------------------------

    @Test
    fun tTheme36a_corruptActiveFile_lastKnownGoodWithBanner() {
        seedAActiveBActive()
        corruptUser(idB)
        val s = process { boot() }
        assertThat(s.themes.dark.themeId).isEqualTo(idA)
        assertThat(s.banner?.kind).isEqualTo(ThemeBannerKind.LOAD_FAILED)
    }

    @Test
    fun tTheme36b_corruptIndex_rebuiltFromFiles_withBanner() {
        seedAActiveBActive()
        corruptIndex()
        process {
            val s = boot()
            assertThat(s.themes.dark.themeId).isEqualTo(idA) // the active pointer was lost: last-known-good
            assertThat(s.banner).isNotNull()
            assertThat(library.currentIndex.entries.map { it.id }).containsExactly(idA, idB)
        }
    }

    @Test
    fun tTheme36c_corruptIndexAndActiveFile_lastKnownGood_activeQuarantined() {
        seedAActiveBActive()
        corruptIndex()
        corruptUser(idB)
        process {
            val s = boot()
            assertThat(s.themes.dark.themeId).isEqualTo(idA)
            assertThat(s.banner).isNotNull()
            assertThat(library.currentIndex.entries.map { it.id }).containsExactly(idA)
        }
        assertThat(File(themesDir, "quarantine/$idB.strata-theme.json").isFile).isTrue()
    }

    @Test
    fun tTheme36d_everythingCorrupt_strataDefaultWithBanner() {
        seedAActiveBActive()
        corruptIndex()
        corruptUser(idA)
        corruptUser(idB)
        File(themesDir, "lkg.strata-theme.json").writeText("[]")
        val s = process { boot() }
        assertThat(s.activeId).isEqualTo(StrataDefault.ID)
        assertThat(s.banner?.kind).isEqualTo(ThemeBannerKind.LOAD_FAILED)
    }

    // ---- T-LIFE-13 --------------------------------------------------------------------------------

    /** Two starts die before they are known good (a first-frame crash): the third is safe mode with the banner. */
    @Test
    fun tLife13_twoFailedStarts_thirdIsSafeMode() {
        process {
            boot()
            repo.apply(paperId)
            await { it.activeId == paperId }
            // The process dies during the first frame: no onFirstFrame, no onBootSucceeded.
        }
        clock.advanceBy(20_000)
        process {
            val s = boot()
            assertThat(s.safeMode).isFalse()
            assertThat(s.themes.light.themeId).isEqualTo(paperId) // first repeat: the theme gets one more try
        }
        clock.advanceBy(20_000)
        process {
            val s = boot()
            assertThat(s.safeMode).isTrue()
            assertThat(s.banner?.kind).isEqualTo(ThemeBannerKind.SAFE_MODE)
            assertThat(s.themes.light.themeId).isEqualTo(StrataDefault.ID)
            // Safe mode is known good once it survives; the next start is normal again.
            renderStable()
        }
        val after = process { boot() }
        assertThat(after.safeMode).isFalse()
        assertThat(after.banner).isNull()
    }

    @Test
    fun tLife13_failedStartsOutsideTheWindow_noSafeMode() {
        process { boot() }
        clock.advanceBy(BootAttempts.WINDOW_MS + 1)
        process { boot() }
        clock.advanceBy(BootAttempts.WINDOW_MS + 1)
        assertThat(process { boot() }.safeMode).isFalse()
    }

    // ---- T-THEME-05 (R half) ---------------------------------------------------------------------

    @Test
    fun tTheme05_systemModeResolvesBothVariants_overrideWins_wallpaperFollowsInputs() {
        process {
            boot()
            repo.apply(harborId) // mode system
            var s = await { it.activeId == harborId }
            assertThat(s.themes.light.variant).isEqualTo(Variant.LIGHT)
            assertThat(s.themes.dark.variant).isEqualTo(Variant.DARK)

            settings.set(Settings(appearanceOverride = AppearanceSetting.DARK))
            s = await { it.themes.light.variant == Variant.DARK }
            assertThat(s.themes.dark.variant).isEqualTo(Variant.DARK)
            settings.set(Settings.DEFAULT)

            repo.apply(duskId) // mode wallpaper: API 31+ dark unless the wallpaper supports dark text
            platform.value =
                PlatformInputs(
                    apiLevel = 34,
                    wallpaperColors = WallpaperColorsInput(0xFFEEEEEE.toInt(), supportsDarkText = true),
                )
            s = await { it.activeId == duskId && it.themes.dark.variant == Variant.LIGHT }
            assertThat(s.themes.light.variant).isEqualTo(Variant.LIGHT)
            platform.value = PlatformInputs(apiLevel = 34, wallpaperColors = WallpaperColorsInput(0xFF101010.toInt()))
            s = await { it.themes.light.variant == Variant.DARK }
            assertThat(s.activeId).isEqualTo(duskId)
        }
    }

    // ---- T-THEME-39 -------------------------------------------------------------------------------

    /** Switching through every theme never touches global settings (§6); the repository has no other store. */
    @Test
    fun tTheme39_switchingThemesKeepsGlobalSettings() {
        val before =
            Settings(
                hapticsEnabled = false,
                homePressOpensSearch = true,
                showHiddenInSearch = false,
                allowRotation = true,
            )
        settings.set(before)
        process {
            boot()
            val ids = runBlocking { repo.library() }.map { it.id }
            for (id in ids + ids.reversed()) {
                repo.apply(id)
                await { it.activeId.equals(id, ignoreCase = true) }
                repo.onFirstFrame(repo.state.value.applyGeneration)
            }
            settle()
        }
        assertThat(settings.value).isEqualTo(before)
    }

    // ---- last-known-good is only ever an applied, resolved theme ----------------------------------

    private val lkgFile get() = File(themesDir, "lkg.strata-theme.json")

    private fun lkgName(): String? =
        lkgFile.takeIf { it.isFile }?.readBytes()?.let { bytes ->
            (
                app.strata.launcher.core.theme.parse.ThemeParser().parse(
                    bytes,
                ) as? app.strata.launcher.core.common.Outcome.Ok
            )?.value
                ?.document
                ?.name
        }

    /** A crash-guard fallback stays a fallback after its banner is dismissed: Default never overwrites the LKG. */
    @Test
    fun dismissedFallback_isNotPromotedToLastKnownGood() {
        seedAActiveBActive()
        assertThat(lkgName()).isEqualTo("Alpha")
        process {
            boot()
            repo.apply(paperId)
            await { it.activeId == paperId }
            repo.onBootSucceeded() // only the theme guard is under test, not boot_attempts
            settle()
        }
        clock.advanceBy(20_000)
        process {
            boot()
            repo.onBootSucceeded()
            settle()
        }
        clock.advanceBy(20_000)
        process {
            val s = boot()
            assertThat(s.banner?.kind).isEqualTo(ThemeBannerKind.CRASH_GUARD)
            assertThat(s.fallback).isTrue()
            repo.dismissBanner()
            assertThat(await { it.banner == null }.fallback).isTrue()
            renderStable()
        }
        assertThat(lkgName()).isEqualTo("Alpha")
    }

    /** A theme whose resolution threw renders Strata Default and is never copied to last-known-good. */
    @Test
    fun resolutionFallback_isNotPromotedToLastKnownGood() {
        val packId = "dddddddd-0000-4000-8000-000000000004"
        val pack =
            (
                JsonParser.parse(
                    """{"schemaVersion":1,"id":"$packId","name":"Pack","icons":{"pack":{"package":"com.example.pack"}}}"""
                        .toByteArray(),
                ) as app.strata.launcher.core.common.Outcome.Ok
            ).value as JsonObject
        // contains() throws inside the resolver; size and iteration work, so StateFlow equality does not.
        val exploding =
            object : AbstractSet<String>() {
                override val size: Int get() = 1

                override fun iterator(): Iterator<String> = listOf("x").iterator()

                override fun contains(element: String): Boolean = throw IllegalStateException("boom")
            }
        process {
            boot()
            runBlocking { withContext(Dispatchers.IO) { library.save(pack) } }
            platform.value = PlatformInputs(apiLevel = 34, installedIconPacks = exploding)
            repo.apply(packId)
            val s = await { it.activeId == packId }
            assertThat(s.fallback).isTrue()
            assertThat(s.themes.light.themeId).isEqualTo(StrataDefault.ID)
            renderStable()
        }
        assertThat(lkgFile.exists()).isFalse()
    }

    /** Applying a theme in safe mode ends safe mode for the theme; once it renders stably it becomes LKG. */
    @Test
    fun applyInSafeMode_leavesSafeMode_andCanBecomeLastKnownGood() {
        repeat(3) {
            process { boot() } // three starts that never become known good
            clock.advanceBy(10_000)
        }
        process {
            assertThat(boot().safeMode).isTrue()
            repo.apply(harborId)
            val s = await { it.activeId == harborId }
            assertThat(s.safeMode).isFalse()
            assertThat(s.fallback).isFalse()
            assertThat(s.banner).isNull()
            renderStable()
        }
        assertThat(lkgName()).isEqualTo("Harbor")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
