package app.strata.launcher.home

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.core.ui.StrayIme
import app.strata.launcher.feature.home.FavEditTestTags
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.home.SheetTestTags
import app.strata.launcher.feature.home.list.AppListTestTags
import app.strata.launcher.feature.onboarding.OnboardingTestTags
import app.strata.launcher.feature.search.SearchTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T-HOME-14 (R): a focused text field in the home window keeps its soft keyboard. The owner's Galaxy A56 report: the
 * keyboard of the Edit favourites picker closed by itself. Home's stray-keyboard guard hid every keyboard outside
 * search and rename. Each field type is focused, the keyboard is shown through IME window insets, and the guard must
 * not hide it; with no field focused it still must (the T-HOME-09 fix stays).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardStaysTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun home(
        intent: Intent = KeyboardFixture.homeIntent(context),
        onboarded: Boolean = true,
    ): HomeActivity {
        KeyboardFixture.graph(onboarded).install()
        val activity = Robolectric.buildActivity(HomeActivity::class.java, intent).setup().get()
        idle()
        return activity
    }

    /** What the window reports while the soft keyboard is up (or down): IME insets on the Compose view. */
    private fun ime(
        activity: HomeActivity,
        visible: Boolean,
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), if (visible) Insets.of(0, 0, 0, IME_HEIGHT) else Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), visible)
                .build()
        ViewCompat.dispatchApplyWindowInsets(content, insets)
        idle()
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        idle()
    }

    private fun assertKept(
        activity: HomeActivity,
        tag: String,
    ) {
        ime(activity, visible = true)
        // A settle: the guard reacts on the next frames, as on a device after the keyboard animation.
        repeat(3) { idle() }
        compose.onNodeWithTag(tag).assertIsFocused()
        assertThat(activity.strayImeHides).isEqualTo(0)
    }

    @Test
    fun tHome14_strayKeyboardAtRestIsStillHidden() {
        val activity = home()
        ime(activity, visible = true)
        assertThat(activity.strayImeHides).isEqualTo(1)
    }

    @Test
    fun tHome14_favouritesPickerKeepsKeyboard() {
        val activity = home(KeyboardFixture.homeIntent(context).setAction(HomeIntents.ACTION_EDIT_FAVORITES))
        compose.onNodeWithTag(FavEditTestTags.ADD_APPS).performClick()
        idle()
        focus(FavEditTestTags.PICKER_SEARCH)
        // The screen state does not list the picker (imeAllowed is false, no onboarding): only the focused imeField
        // keeps the keyboard.
        assertKept(activity, FavEditTestTags.PICKER_SEARCH)
        // Closing the picker removes the field: the keyboard still reported up is stray and is hidden, once.
        compose.onNodeWithTag(FavEditTestTags.PICKER_CANCEL).performClick()
        idle()
        compose.onNodeWithTag(FavEditTestTags.PICKER_SEARCH).assertDoesNotExist()
        assertThat(activity.strayImeHides).isEqualTo(1)
    }

    @Test
    fun tHome14_onboardingPickerInHomeKeepsKeyboard() {
        val activity = home(onboarded = false)
        compose.onNodeWithTag(OnboardingTestTags.CONTINUE).performClick()
        idle()
        focus(OnboardingTestTags.FAVORITES_SEARCH)
        assertKept(activity, OnboardingTestTags.FAVORITES_SEARCH)
    }

    @Test
    fun tHome14_searchFieldKeepsKeyboardAndCloseHidesIt() {
        val activity = home()
        compose.onNodeWithTag(HomeTestTags.PILL).performClick()
        idle()
        focus(SearchTestTags.FIELD)
        assertKept(activity, SearchTestTags.FIELD)
        // Closing search removes the field: a keyboard still up over home at rest is stray again.
        // The keyboard is still reported up when the field goes (a late show): the guard hides it, once.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        compose.onNodeWithTag(SearchTestTags.LAYER).assertDoesNotExist()
        assertThat(activity.strayImeHides).isEqualTo(1)
    }

    @Test
    fun tHome14_renameFieldKeepsKeyboard() {
        val activity = home()
        compose.onNodeWithTag(HomeTestTags.ALL_APPS).performClick()
        idle()
        compose
            .onNodeWithTag(AppListTestTags.row(KeyboardFixture.alpha))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        idle()
        compose.onNodeWithTag(SheetTestTags.RENAME).performClick()
        idle()
        focus(SheetTestTags.RENAME_FIELD)
        assertKept(activity, SheetTestTags.RENAME_FIELD)
    }

    @Test
    fun decision() {
        assertThat(StrayIme.shouldHide(imeVisible = true, allowed = false, fieldFocused = false)).isTrue()
        assertThat(StrayIme.shouldHide(imeVisible = true, allowed = false, fieldFocused = true)).isFalse()
        assertThat(StrayIme.shouldHide(imeVisible = true, allowed = true, fieldFocused = false)).isFalse()
        assertThat(StrayIme.shouldHide(imeVisible = false, allowed = false, fieldFocused = false)).isFalse()
    }

    private companion object {
        const val IME_HEIGHT = 900
    }
}
