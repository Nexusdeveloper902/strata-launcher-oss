package app.strata.launcher.home

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ARCHITECTURE §13 / T-SEC-03: only MAIN + HOME counts as a Home press. */
class HomeIntentsTest {
    @Test
    fun mainHomeIsHomePress() {
        assertThat(
            HomeIntents.isHomePress(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_HOME, Intent.CATEGORY_DEFAULT)),
        ).isTrue()
    }

    @Test
    fun otherIntentsAreIgnored() {
        assertThat(HomeIntents.isHomePress(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER))).isFalse()
        assertThat(HomeIntents.isHomePress(Intent.ACTION_VIEW, setOf(Intent.CATEGORY_HOME))).isFalse()
        assertThat(HomeIntents.isHomePress(null, null)).isFalse()
    }
}
