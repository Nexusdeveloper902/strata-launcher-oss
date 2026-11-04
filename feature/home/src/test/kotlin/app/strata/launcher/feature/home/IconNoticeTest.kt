package app.strata.launcher.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconPackInfo
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.store.PackNotice
import app.strata.launcher.core.theme.store.ThemePair
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.testing.fakes.FakeThemeRepository
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home's icon-pack notices: FR-THEME-25 (T-THEME-33 home half) and FR-APPS-06 (T-APPS-10 R half). */
@RunWith(AndroidJUnit4::class)
class IconNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private val maps = app("Maps")
    private val mail = app("Mail")
    private val pack = "fixture.pack.ok"

    private fun packInfo(usable: Boolean = true) = IconPackInfo(pack, "OK Pack", 1, usable)

    /**
     * T-THEME-33: the notice names the pack; the store button fires `market://details?id=<pkg>` through SystemIntents
     * (built from the package name only) and hides the notice for this apply. Strata itself fetches nothing.
     */
    @Test
    fun tTheme33_storeButtonOpensListingOnce() {
        val themes =
            FakeThemeRepository(ThemeState(loaded = true, packNotice = PackNotice(pack, "OK Pack", unusable = false)))
        val h = HomeHarness(listOf(maps), themeRepository = themes)
        h.setContent(compose)
        compose.onNodeWithText("This theme uses the icon pack OK Pack, which is not installed.").assertExists()
        compose.onNodeWithTag(HomeTestTags.ICON_NOTICE_STORE).performClick()
        compose.waitForIdle()
        assertThat(h.systemIntents.storeListings).containsExactly(pack)
        assertThat(themes.packNoticeDismissals).isEqualTo(1)
        compose.onNodeWithTag(HomeTestTags.ICON_NOTICE).assertDoesNotExist()
    }

    @Test
    fun unusablePackHasItsOwnText() {
        val themes =
            FakeThemeRepository(ThemeState(loaded = true, packNotice = PackNotice(pack, null, unusable = true)))
        HomeHarness(listOf(maps), themeRepository = themes).setContent(compose)
        compose
            .onNodeWithText("This theme uses the icon pack $pack, which can't be read. System icons are shown instead.")
            .assertExists()
    }

    /**
     * T-APPS-10 (R half): X keeps its pack icon (the override) through three theme switches; when the pack is
     * uninstalled, X's request carries no usable override any more (the provider falls back) and a notice shows once.
     */
    @Test
    fun tApps10_overrideSurvivesThemesAndRemovalNoticesOnce() {
        val themes = FakeThemeRepository()
        val pair = ThemePair.DEFAULT
        listOf("t1", "t2", "t3").forEach { themes.themes[it] = pair }
        val h =
            HomeHarness(
                listOf(maps, mail),
                themeRepository = themes,
                iconPacks = kotlinx.coroutines.flow.MutableStateFlow(IconPacks(listOf(packInfo()), true)),
            )
        val icon = IconOverride(pack, "maps_icon")
        runBlocking { h.overrides.setIconOverride(maps.key, ProfileKind.MAIN, icon) }
        h.setContent(compose)

        fun mapsOverride() =
            h.state.list!!
                .allApps
                .first { it.key == maps.key }
                .iconOverride
        assertThat(mapsOverride()).isEqualTo(icon)
        for (id in listOf("t1", "t2", "t3")) {
            themes.apply(id)
            compose.waitForIdle()
            assertThat(mapsOverride()).isEqualTo(icon) // FR-THEME-31: themes never touch per-app icons
        }
        assertThat(h.state.iconNotice).isNull()

        // Uninstall the pack.
        h.iconPacks.value = IconPacks(emptyList(), discovered = true)
        compose.waitForIdle()
        assertThat(h.state.iconNotice).isEqualTo(IconNotice.OverridePackGone(pack))
        compose.onNodeWithTag(HomeTestTags.ICON_NOTICE).assertExists()
        h.vm.onAction(HomeAction.DismissIconNotice)
        compose.waitForIdle()
        assertThat(h.state.iconNotice).isNull()
        // Shown once: another package event does not bring it back.
        h.iconPacks.update { it.copy(packs = listOf(IconPackInfo("other.pack", "Other", 1, true))) }
        compose.waitForIdle()
        assertThat(h.state.iconNotice).isNull()
        // The override itself is kept (the pack may come back), so the app keeps its choice after a reinstall.
        assertThat(h.overrides.state.value[maps.key]).isEqualTo(AppOverride(maps.key, icon = icon))
    }

    @Test
    fun unusableOverridePackAlsoNotices() {
        val h =
            HomeHarness(
                listOf(maps),
                iconPacks = kotlinx.coroutines.flow.MutableStateFlow(IconPacks(listOf(packInfo(usable = false)), true)),
            )
        runBlocking { h.overrides.setIconOverride(maps.key, ProfileKind.MAIN, IconOverride(pack, "x")) }
        h.setContent(compose)
        assertThat(h.state.iconNotice).isEqualTo(IconNotice.OverridePackGone(pack))
    }

    @Test
    fun privateProfileOverridesAreNotWritten() {
        val h = HomeHarness(listOf(maps))
        runBlocking { h.overrides.setIconOverride(maps.key, ProfileKind.PRIVATE, IconOverride(pack, "x")) }
        assertThat(h.overrides.state.value).isEmpty()
        assertThat(StrataDefault.ID).isNotEmpty()
    }
}
