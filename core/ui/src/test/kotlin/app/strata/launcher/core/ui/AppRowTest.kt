package app.strata.launcher.core.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.theme.StrataDefault
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FR-LIST-03 base row and index helpers (Robolectric Compose, level C). */
@RunWith(AndroidJUnit4::class)
class AppRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(style: BadgeStyle) {
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = true)) {
                AppRow(
                    label = "Mail",
                    onClick = {},
                    profileKind = ProfileKind.WORK,
                    badge = 2,
                    badgeStyle = style,
                )
            }
        }
    }

    /** T-LIST-06 (C half), dot mode: icon, label, work badge and a described dot. */
    @Test
    fun tList06_rowContent_dot() {
        row(BadgeStyle.DOT)
        compose.onNodeWithTag(AppRowTestTags.ICON, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AppRowTestTags.LABEL, useUnmergedTree = true).assertExists()
        compose
            .onNodeWithTag(AppRowTestTags.WORK_BADGE, useUnmergedTree = true)
            .assertContentDescriptionContains("Work")
        compose
            .onNodeWithTag(AppRowTestTags.BADGE_DOT, useUnmergedTree = true)
            .assertContentDescriptionContains("2 notifications")
        compose.onNodeWithTag(AppRowTestTags.BADGE_COUNT, useUnmergedTree = true).assertDoesNotExist()
    }

    /** T-LIST-06 (C half), count mode: the count pill shows the number. */
    @Test
    fun tList06_rowContent_count() {
        row(BadgeStyle.COUNT)
        compose
            .onNodeWithTag(AppRowTestTags.BADGE_COUNT, useUnmergedTree = true)
            .assertContentDescriptionContains("2 notifications")
        compose.onNodeWithText("2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AppRowTestTags.BADGE_DOT, useUnmergedTree = true).assertDoesNotExist()
    }

    /** The row is one accessibility node that reads label and badges together (FR-A11Y groundwork). */
    @Test
    fun rowIsOneMergedNode() {
        row(BadgeStyle.DOT)
        val node = compose.onNodeWithText("Mail").fetchSemanticsNode()
        val descriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        assertThat(descriptions).containsAtLeast("Work", "2 notifications")
    }

    /** THEME_SYSTEM §4.6: icons visible, labels hidden: the label is invisible but still the row's accessible name. */
    @Test
    fun hiddenLabelStaysInSemantics() {
        val base = StrataDefault.resolved(systemDark = true)
        compose.setContent {
            StrataTheme(base.copy(icons = base.icons.copy(visible = true, labelsVisible = false))) {
                AppRow(label = "Mail", onClick = {})
            }
        }
        compose.onNodeWithTag(AppRowTestTags.LABEL, useUnmergedTree = true).assertDoesNotExist()
        val node = compose.onNode(hasClickAction()).fetchSemanticsNode()
        val descriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        assertThat(descriptions).contains("Mail")
    }

    @Test
    fun monogramIsFirstGrapheme() {
        assertThat(firstGrapheme("mail")).isEqualTo("M")
        assertThat(firstGrapheme("  Éclair")).isEqualTo("É")
        assertThat(firstGrapheme("👍🏽 Likes")).isEqualTo("👍🏽")
        assertThat(firstGrapheme("")).isEmpty()
    }

    @Test
    fun indexMath() {
        assertThat(IndexMath.letterAt(y = 0f, top = 0f, height = 260f, count = 26)).isEqualTo(0)
        assertThat(IndexMath.letterAt(y = 255f, top = 0f, height = 260f, count = 26)).isEqualTo(25)
        assertThat(IndexMath.letterAt(y = 999f, top = 0f, height = 260f, count = 26)).isEqualTo(25)
        val letters =
            listOf(IndexLetter("P", 0, 1), IndexLetter("Q", -1, 0), IndexLetter("R", 1, 2), IndexLetter("S", -1, 0))
        assertThat(IndexMath.resolve(letters, 1)).isEqualTo(2) // Q → R
        assertThat(IndexMath.resolve(letters, 3)).isEqualTo(2) // last empty → previous
        val slots = IndexMath.slots(count = 40, maxSlots = 21)
        assertThat(slots).hasSize(21)
        assertThat(slots.first().first).isEqualTo(0)
        assertThat(slots.last().last).isEqualTo(39)
        assertThat(IndexMath.slots(count = 26, maxSlots = 40)).hasSize(26)
    }
}
