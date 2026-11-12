package app.strata.launcher.feature.widgets

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.IconImage
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.widgets.WidgetPreview
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.feature.widgets.WidgetFixtures.clock
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S-WPICK and the header area rendered (level C): T-WIDGET-02 names and groups, T-WIDGET-06 heights and a11y actions. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h800dp-xxhdpi")
class WidgetScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val noPreviews =
        object : WidgetPreviewSource {
            var requests = 0

            override suspend fun preview(
                ref: WidgetProviderRef,
                maxWidthPx: Int,
                maxHeightPx: Int,
            ): WidgetPreview {
                requests++
                return WidgetPreview.None
            }

            override suspend fun icon(
                ref: WidgetProviderRef,
                sizePx: Int,
            ): IconImage? = null
        }

    @Test
    fun tWidget02_pickerGroupsByApp_withAccessibleNames() {
        val picked = mutableListOf<WidgetPickerAction>()
        val state = WidgetPickerState(loading = false, groups = WidgetPickerViewModel.group(WidgetFixtures.all))
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = false)) {
                WidgetPickerScreen(state, { picked += it }, noPreviews, {}, SnackbarHostState())
            }
        }
        compose.onNodeWithTag(WidgetTestTags.group("fixture.clock")).assertExists()
        compose.onNodeWithTag(WidgetTestTags.group("fixture.notes")).assertExists()
        val row = compose.onNodeWithTag(WidgetTestTags.provider(clock.ref.component)).fetchSemanticsNode()
        assertThat(row.config[SemanticsProperties.ContentDescription]).containsExactly("Clock, widget from Clock app")
        compose.onNodeWithTag(WidgetTestTags.provider(clock.ref.component)).performClick()
        assertThat(picked).containsExactly(WidgetPickerAction.Pick(clock.ref))
        compose.waitUntil(5_000) { noPreviews.requests == 3 }
    }

    @Test
    fun replaceClockChoiceIsAsked() {
        val picked = mutableListOf<WidgetPickerAction>()
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = false)) {
                WidgetPickerScreen(
                    WidgetPickerState(loading = false, askReplaceClock = true),
                    { picked += it },
                    noPreviews,
                    {},
                    SnackbarHostState(),
                )
            }
        }
        compose.onNodeWithTag(WidgetTestTags.CLOCK_REPLACE).performClick()
        assertThat(picked).containsExactly(WidgetPickerAction.ReplaceClock(true))
    }

    private fun area(
        state: WidgetAreaState,
        actions: MutableList<WidgetAreaAction> = mutableListOf(),
    ) {
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = false)) {
                WidgetArea(state, { actions += it }, hostContent = { _, _, m -> WidgetErrorTile(m) })
                WidgetMenu(state, { actions += it })
            }
        }
    }

    @Test
    fun tWidget06_heightOfStep3_andResizeActionsForAccessibility() {
        val actions = mutableListOf<WidgetAreaAction>()
        // Window 800 dp: steps 100 … 400 dp for a 100 dp minimum.
        area(WidgetAreaState(WidgetPlacement(3, clock.ref, heightStep = 3), minHeightDp = 100), actions)
        compose.onNodeWithTag(WidgetTestTags.HOST).assertHeightIsEqualTo(300.dp)
        val node = compose.onNodeWithTag(WidgetTestTags.HOST).fetchSemanticsNode()
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertThat(labels).containsAtLeast("Resize taller", "Resize shorter", "Remove")
        node.config[SemanticsActions.CustomActions].first { it.label == "Resize taller" }.action()
        assertThat(actions).containsExactly(WidgetAreaAction.Taller)
    }

    @Test
    fun resizeBarAndMenu() {
        val actions = mutableListOf<WidgetAreaAction>()
        var state by mutableStateOf(WidgetAreaState(WidgetPlacement(3, clock.ref, heightStep = 1), resizing = true))
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = false)) {
                WidgetArea(state, { actions += it }, hostContent = { _, _, m -> WidgetErrorTile(m) })
                WidgetMenu(state, { actions += it })
            }
        }
        compose.onNodeWithTag(WidgetTestTags.TALLER).performClick()
        state = state.copy(resizing = false, menuOpen = true)
        compose.onNodeWithTag(WidgetTestTags.MENU_REMOVE).performClick()
        assertThat(actions).containsExactly(WidgetAreaAction.Taller, WidgetAreaAction.Remove).inOrder()
    }

    @Test
    fun tWidget09_placeholderOffersReAddAndRemove() {
        val actions = mutableListOf<WidgetAreaAction>()
        area(WidgetAreaState(WidgetPlacement(3, clock.ref, state = WidgetPlacementState.UNAVAILABLE)), actions)
        compose.onNodeWithTag(WidgetTestTags.PLACEHOLDER).assertExists()
        compose.onNodeWithTag(WidgetTestTags.READD).performClick()
        compose.onNodeWithTag(WidgetTestTags.PLACEHOLDER_REMOVE).performClick()
        assertThat(actions).containsExactly(WidgetAreaAction.ReAdd, WidgetAreaAction.Remove).inOrder()
        assertThat(compose.onAllNodesWithTag(WidgetTestTags.HOST).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun safeMode_nothingRendered() {
        area(WidgetAreaState(WidgetPlacement(3, clock.ref), suppressed = true))
        assertThat(compose.onAllNodesWithTag(WidgetTestTags.AREA).fetchSemanticsNodes()).isEmpty()
    }
}
