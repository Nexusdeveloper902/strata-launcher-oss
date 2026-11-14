package app.strata.launcher.core.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NFR-A11Y-02 / review fix: only the font-scale growth of index and monogram glyphs is capped, never the theme size. */
class FontScaleCapTest {
    private val theme = TextStyle(fontSize = 20.sp, lineHeight = 26.sp)

    @Test
    fun scaleOne_keepsTheThemeSizeEvenInASmallBox() {
        assertThat(theme.capFontScaleGrowth(Density(3f, fontScale = 1f), maxDp = 11.dp)).isEqualTo(theme)
    }

    @Test
    fun belowOne_isUnchanged() {
        assertThat(theme.capFontScaleGrowth(Density(3f, fontScale = 0.85f), maxDp = 11.dp)).isEqualTo(theme)
    }

    @Test
    fun largeScale_growsUpToTheBox_andKeepsTheLineHeightProportion() {
        val d = Density(3f, fontScale = 2f)
        // 20 sp at scale 2 renders larger than the 30 dp box; it stops at 30 dp (the platform's nonlinear scaling
        // decides how many sp that is).
        assertThat(with(d) { theme.fontSize.toDp().value }).isGreaterThan(30f)
        val capped = theme.capFontScaleGrowth(d, maxDp = 30.dp)
        assertThat(with(d) { capped.fontSize.toDp().value }).isWithin(0.05f).of(30f)
        assertThat(capped.lineHeight.value / capped.fontSize.value).isWithin(0.001f).of(26f / 20f)
    }

    @Test
    fun largeScale_neverBelowTheThemeSize() {
        val d = Density(3f, fontScale = 2f)
        val capped = theme.capFontScaleGrowth(d, maxDp = 11.dp)
        // The box is smaller than the theme size: the result still renders at the theme's 20 dp.
        assertThat(with(d) { capped.fontSize.toDp().value }).isWithin(0.05f).of(20f)
    }

    @Test
    fun largeScale_thatFits_isUnchanged() {
        assertThat(theme.capFontScaleGrowth(Density(3f, fontScale = 1.3f), maxDp = 40.dp)).isEqualTo(theme)
    }
}
