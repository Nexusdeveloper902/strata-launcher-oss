package app.strata.launcher.feature.theme

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.theme.store.PresetWallpaper
import app.strata.launcher.core.ui.StrataDefaultTheme
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.MapBuiltInThemeSource
import app.strata.launcher.testing.fixtures.BundleFixtures
import app.strata.launcher.testing.fixtures.ThemeFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * T-THEME-61 (UI half): a built-in preset with a wallpaper set (THEME_SYSTEM §14.2, FR-THEME-36). Applying it opens the
 * picker; picking an image hands exactly that image to the system wallpaper activity; "Not now" starts nothing; the
 * row menu's "Set wallpaper" opens the picker without applying; a preset without a set has no such item.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PresetWallpaperPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val images = (1..3).map { BundleFixtures.webpHeader(64 + it, 128) }
    private val shas = images.map(::sha256)
    private val preset = "set.strata-theme.json"
    private val presetId = "5e7c0a11-0000-4000-8000-00000000c0de"
    private val h =
        ThemeHarness(
            builtIns =
                MapBuiltInThemeSource(
                    files =
                        ThemeFixtures.samples() +
                            (
                                preset to
                                    (
                                        """{"schemaVersion":1,"id":"$presetId","name":"With Wallpapers",""" +
                                            """"wallpaper":{"show":true,"image":{"sha256":"${shas[0]}"}}}"""
                                    ).toByteArray()
                            ),
                    wallpaperSets = mapOf(preset to shas.mapIndexed { i, s -> PresetWallpaper(s, "Picture ${i + 1}") }),
                    images = shas.zip(images).toMap(),
                ),
        )
    private val undoScope = TestScope(StandardTestDispatcher())

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun show(): ThemeLibraryViewModel {
        val vm = ThemeLibraryViewModel(h.repo, h.repo, h.documents, h.shared, h.intents, undoScope)
        compose.setContent {
            StrataDefaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                ThemeLibraryScreen(state, vm::onAction)
            }
        }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun tTheme61_applyOpensPickerAndHandsOffThePickedImage() {
        val vm = show()
        compose.onNodeWithTag(ThemeTestTags.row(presetId)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_PICKER).assertExists()
        assertThat(
            vm.state.value.wallpaperChoices
                .map { it.sha256 },
        ).containsExactlyElementsIn(shas).inOrder()
        compose.onNodeWithTag(ThemeTestTags.wallpaperChoice(shas[1])).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_PICKER).assertDoesNotExist()
        val (kind, uri) = h.intents.wallpaperHandoffs.single()
        assertThat(kind).isEqualTo(FakeSystemIntents.CROP)
        assertThat(h.shared.published[uri]).isEqualTo(images[1])
    }

    @Test
    fun tTheme61_notNowStartsNothing() {
        show()
        compose.onNodeWithTag(ThemeTestTags.row(presetId)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_NO).performClick()
        compose.waitForIdle()
        assertThat(h.intents.wallpaperHandoffs).isEmpty()
        assertThat(h.shared.published).isEmpty()
    }

    @Test
    fun tTheme61_noSetNoMenuItem() {
        show()
        compose.onNodeWithTag(ThemeTestTags.menu(PAPER_ID)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.menuItem(PAPER_ID, "edit")).assertExists()
        compose.onNodeWithTag(ThemeTestTags.menuItem(PAPER_ID, "wallpaper")).assertDoesNotExist()
    }

    @Test
    fun tTheme61_menuOffersTheSetWithoutApplying() {
        val vm = show()
        val before = vm.state.value.activeId
        compose.onNodeWithTag(ThemeTestTags.menu(presetId)).performClick()
        compose.onNodeWithTag(ThemeTestTags.menuItem(presetId, "wallpaper")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ThemeTestTags.WALLPAPER_PICKER).assertExists()
        assertThat(vm.state.value.activeId).isEqualTo(before)
        compose.onNodeWithTag(ThemeTestTags.wallpaperChoice(shas[2])).performClick()
        compose.waitForIdle()
        assertThat(
            h.shared.published.values
                .single(),
        ).isEqualTo(images[2])
        assertThat(vm.state.value.activeId).isEqualTo(before)
    }

    private companion object {
        const val PAPER_ID = ThemeHarness.PAPER

        fun sha256(b: ByteArray): String =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(b)
                .joinToString("") { "%02x".format(it) }
    }
}
