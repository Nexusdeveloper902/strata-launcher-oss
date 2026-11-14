package app.strata.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.a11y.KeyboardFixture
import app.strata.launcher.feature.home.FavEditTestTags
import app.strata.launcher.feature.settings.SettingsPageTags
import app.strata.launcher.feature.settings.SettingsTestTags
import app.strata.launcher.settings.SettingsActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T-FAV-09 (R): Settings → Home → "Favourites" opens home in Edit favourites; the editor shows the current favourites
 * (also when home is started by that intent, before its list has loaded) and offers "Add apps".
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FavoritesEntryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun tFav09_settingsRowOpensEditFavourites() {
        KeyboardFixture.graph().install()
        val settings =
            Robolectric
                .buildActivity(
                    SettingsActivity::class.java,
                    Intent(Intent.ACTION_APPLICATION_PREFERENCES).setComponent(
                        ComponentName(context, SettingsActivity::class.java),
                    ),
                ).setup()
        idle()
        compose.onNodeWithTag(SettingsTestTags.ROOT).performScrollToNode(hasTestTag(SettingsPageTags.FAVORITES))
        compose.onNodeWithTag(SettingsPageTags.FAVORITES).performClick()
        idle()
        val started = shadowOf(settings.get()).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(HomeActivity::class.java.name)
        assertThat(started.action).isEqualTo(HomeIntents.ACTION_EDIT_FAVORITES)
        assertThat(started.extras).isNull()
        settings.pause().stop().destroy()
        idle()

        Robolectric.buildActivity(HomeActivity::class.java, started).setup()
        idle()
        compose.onNodeWithTag(FavEditTestTags.SCREEN).assertExists()
        compose.onNodeWithTag(FavEditTestTags.row(KeyboardFixture.maps)).assertExists()
        compose.onNodeWithTag(FavEditTestTags.ADD_APPS).assertExists()
    }
}
