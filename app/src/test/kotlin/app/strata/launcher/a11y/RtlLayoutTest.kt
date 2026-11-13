package app.strata.launcher.a11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.theme.store.ThemeState
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.core.ui.AppRowTestTags
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.settings.SettingsActivity
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.strata.launcher.core.theme.model.Alignment as ThemeAlignment

/**
 * T-I18N-03 (S as layout bounds), NFR-I18N-02, VISUAL_SPEC §8.5: in ar and he the index moves to the left, list rows
 * put the icon on the right of the label, start-aligned header and favourites sit on the right, sheet items and
 * settings rows are right-aligned. en is the control (the same checks give the mirror image). Per built-in preset in
 * [PresetAccessibilityTest] (T-THEME-47).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RtlLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "ar-w411dp-h891dp-xxhdpi")
    fun arabic() = mirrored(rtl = true)

    @Test
    @Config(qualifiers = "iw-w411dp-h891dp-xxhdpi")
    fun hebrew() = mirrored(rtl = true)

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    fun englishControl() = mirrored(rtl = false)

    private fun mirrored(rtl: Boolean) = checkMirrored(compose, context, rtl, ThemeState(loaded = true))

    companion object {
        /** The VISUAL_SPEC §8.5 checks for one theme; [rtl] = the expected direction. */
        fun checkMirrored(
            compose: androidx.compose.ui.test.junit4.ComposeTestRule,
            context: Context,
            rtl: Boolean,
            theme: ThemeState,
            indexAtEnd: Boolean = true,
        ) {
            fun idle() {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }

            fun b(
                tag: String,
                unmerged: Boolean = false,
            ) = compose
                .onAllNodesWithTag(tag, useUnmergedTree = unmerged)
                .fetchSemanticsNodes()
                .first()
                .boundsInRoot

            fun onRight(r: Rect, w: Float) = r.center.x > w / 2
            KeyboardFixture.graph(theme = theme).install()
            Robolectric.buildActivity(HomeActivity::class.java, KeyboardFixture.homeIntent(context)).setup()
            idle()
            val w =
                compose
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.width
            val dir = if (rtl) "RTL" else "LTR"
            // Home: clock and favourites sit on the side their theme alignment names (start = right in RTL).
            val light = theme.themes.light

            fun expectRight(a: ThemeAlignment): Boolean? =
                when (a) {
                    ThemeAlignment.START -> rtl
                    ThemeAlignment.END -> !rtl
                    ThemeAlignment.CENTER -> null
                }
            expectRight(light.clock.alignment)?.let {
                assertWithMessage("$dir clock side").that(onRight(b(HomeTestTags.CLOCK), w)).isEqualTo(it)
            }
            val favText =
                compose
                    .onAllNodes(
                        hasAnyAncestor(hasTestTag(HomeTestTags.favorite(KeyboardFixture.maps))) and
                            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .firstOrNull()
            if (favText != null) {
                expectRight(light.favorites.alignment)?.let {
                    assertWithMessage("$dir favourite side").that(onRight(favText.boundsInRoot, w)).isEqualTo(it)
                }
            }
            // List: index on the end side (left in RTL), icon before the label in reading order.
            compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
            idle()
            assertWithMessage(
                "$dir index side",
            ).that(onRight(b(AlphabetIndexTestTags.INDEX), w)).isEqualTo(rtl != indexAtEnd)
            val row = AppListTestTags.row(KeyboardFixture.alpha)
            compose.onNodeWithTag(AppListTestTags.LIST).performScrollToNode(hasTestTag(row))
            // Themes without icons (`icons.visible: false`) have only the label.
            if (compose
                    .onAllNodesWithTag(
                        AppRowTestTags.ICON,
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                val icon = b(AppRowTestTags.ICON, unmerged = true)
                val label = b(AppRowTestTags.LABEL, unmerged = true)
                assertWithMessage("$dir icon before label").that(icon.center.x > label.center.x).isEqualTo(rtl)
            }
            // Sheet: items start on the start side.
            compose.onNodeWithTag(row).performSemanticsAction(SemanticsActions.OnLongClick)
            idle()
            val item = b(SheetTestTags.APP_INFO)
            val text =
                compose
                    .onAllNodes(
                        hasAnyAncestor(hasTestTag(SheetTestTags.APP_INFO)) and
                            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .first()
                    .boundsInRoot
            val startGap = if (rtl) item.right - text.right else text.left - item.left
            val endGap = if (rtl) text.left - item.left else item.right - text.right
            assertWithMessage("$dir sheet item text on the start side").that(startGap).isLessThan(endGap)
            // Settings: row titles start on the start side.
            Robolectric
                .buildActivity(
                    SettingsActivity::class.java,
                    Intent(
                        Intent.ACTION_APPLICATION_PREFERENCES,
                    ).setComponent(ComponentName(context, SettingsActivity::class.java)),
                ).setup()
            idle()
            compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(SettingsPageTags.ABOUT_ROW))
            val about = compose.onAllNodesWithTag(SettingsPageTags.ABOUT_ROW).fetchSemanticsNodes().last()
            val title = about.children.firstOrNull()?.boundsInRoot
            if (title != null) {
                val startGap = if (rtl) about.boundsInRoot.right - title.right else title.left - about.boundsInRoot.left
                val endGap = if (rtl) title.left - about.boundsInRoot.left else about.boundsInRoot.right - title.right
                assertWithMessage("$dir settings title on the start side").that(startGap).isLessThan(endGap)
            }
        }
    }
}
