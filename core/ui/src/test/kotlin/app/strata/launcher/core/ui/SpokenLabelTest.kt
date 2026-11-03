package app.strata.launcher.core.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.LabelCase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FR-A11Y-03 spoken labels (Robolectric Compose, level C): the merged description TalkBack reads, in order. */
@RunWith(AndroidJUnit4::class)
class SpokenLabelTest {
    @get:Rule
    val compose = createComposeRule()

    private fun spoken(): String {
        val node = compose.onNode(hasClickAction()).fetchSemanticsNode()
        return node.config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString(", ")
    }

    /** T-A11Y-04: renamed "Nav" with 2 notifications reads "Nav, 2 notifications"; work and hidden add their state. */
    @Test
    fun tA11y04_spokenLabels() {
        val base = StrataDefault.resolved(systemDark = false)
        compose.setContent {
            StrataTheme(base) {
                AppRow(label = "Nav", onClick = {}, badge = 2, badgeStyle = BadgeStyle.COUNT)
            }
        }
        assertThat(spoken()).isEqualTo("Nav, 2 notifications")
    }

    @Test
    fun tA11y04_workAndHidden() {
        val base = StrataDefault.resolved(systemDark = false)
        compose.setContent {
            StrataTheme(base) {
                AppRow(label = "Mail", onClick = {}, profileKind = ProfileKind.WORK, tag = "Hidden")
            }
        }
        assertThat(spoken().lowercase()).isEqualTo("mail, work, hidden")
    }

    /** T-A11Y-05: an upper-case theme shows "MAPS" but speaks the original "Maps". */
    @Test
    fun tA11y05_caseTransformNotSpoken() {
        val base = StrataDefault.resolved(systemDark = false)
        compose.setContent {
            StrataTheme(base.copy(typography = base.typography.copy(labelCase = LabelCase.UPPER))) {
                AppRow(label = "Maps", onClick = {})
            }
        }
        assertThat(spoken()).isEqualTo("Maps")
        val texts =
            compose
                .onNode(hasClickAction())
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .map { it.text }
        assertThat(texts).contains("MAPS")
    }
}
