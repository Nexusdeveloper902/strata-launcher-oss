package app.strata.launcher.feature.search

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.SwipeToCall
import app.strata.launcher.core.platformapi.system.CallMode
import app.strata.launcher.core.platformapi.system.ContactHit
import app.strata.launcher.core.platformapi.system.PermissionState
import app.strata.launcher.core.platformapi.system.PhoneNumber
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.testing.fakes.FakeContactsSource
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FR-SEARCH-09 swipe gesture on the contact row (level C): T-SEARCH-25, and the T-SEARCH-24 chooser on screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp")
class SwipeToCallScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The left `systemGestures` inset Compose saw, in px (proves the injected inset arrived: no vacuous edge test). */
    private var seenEdgeLeft = -1

    private val zed = ContactHit("content://com.android.contacts/contacts/lookup/fx0/0", "Zed Fixture")
    private val zoe = ContactHit("content://com.android.contacts/contacts/lookup/fx1/1", "Zoe Fixture")

    private fun harness(mode: SwipeToCall = SwipeToCall.DIAL) =
        SearchHarness(
            settings = Settings(onboardingDone = true, contactsSearchEnabled = true, swipeToCall = mode),
            contacts =
                FakeContactsSource(
                    permissionState = PermissionState.GRANTED,
                    contacts = listOf(zed, zoe),
                    callPermissionState = PermissionState.DENIED,
                    numbers =
                        mapOf(
                            zed.lookupUri to listOf(PhoneNumber("+1 555 0100", "Mobile")),
                            zoe.lookupUri to
                                listOf(PhoneNumber("+1 555 0120", "Home"), PhoneNumber("+1 555 0121", "Work")),
                        ),
                ),
        )

    private fun show(
        h: SearchHarness,
        seed: String,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            seenEdgeLeft = WindowInsets.systemGestures.getLeft(LocalDensity.current, LayoutDirection.Ltr)
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                StrataTheme(StrataDefault.resolved(systemDark = false)) {
                    val state by h.viewModel.state.collectAsStateWithLifecycle()
                    SearchRoute(
                        state = state,
                        effects = h.viewModel.effects,
                        onAction = h.viewModel::onAction,
                        onClose = {},
                        seed = seed,
                        session = 1,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTestTags.contact(0)).assertExists()
    }

    /** Drags the first contact row horizontally by [dx] (negative = toward the left), from [startFraction] of its width. */
    private fun dragRow(
        dx: Dp,
        startFraction: Float = 0.3f,
    ) {
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput {
            val start = Offset(width * startFraction, centerY)
            swipe(start, start + Offset(dx.toPx(), 0f), durationMillis = 300)
        }
        compose.waitForIdle()
    }

    private fun calls(h: SearchHarness) = h.contacts!!.calls

    /** T-SEARCH-25: past 96 dp toward the end edge (right in LTR) calls once; the dialer for the default setting. */
    @Test
    fun tSearch25_swipeTowardEndCallsOnce() {
        val h = harness()
        show(h, "zed")
        dragRow(140.dp)
        assertThat(calls(h)).containsExactly("+1 555 0100" to CallMode.DIAL)
    }

    /** T-SEARCH-25: a short drag springs back without a call; "Call" shows only while the row is dragged. */
    @Test
    fun tSearch25_shortDragSpringsBack() {
        val h = harness()
        show(h, "zed")
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput {
            down(Offset(width * 0.3f, centerY))
            moveBy(Offset(viewConfiguration.touchSlop + 40.dp.toPx(), 0f))
        }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag(SearchTestTags.CALL_HINT).fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput { up() }
        compose.waitForIdle()
        assertThat(calls(h)).isEmpty()
        assertThat(compose.onAllNodesWithTag(SearchTestTags.CALL_HINT).fetchSemanticsNodes()).isEmpty()
        assertThat(h.viewModel.state.value.query).isEqualTo("zed")
    }

    /** T-SEARCH-25: toward the start edge and vertical drags call nothing. */
    @Test
    fun tSearch25_otherDirectionsCallNothing() {
        val h = harness()
        show(h, "zed")
        dragRow((-140).dp, startFraction = 0.8f)
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput {
            swipe(Offset(width * 0.5f, centerY), Offset(width * 0.5f, centerY - 200.dp.toPx()), durationMillis = 300)
        }
        compose.waitForIdle()
        assertThat(calls(h)).isEmpty()
    }

    /** T-SEARCH-25: RTL mirrors the gesture: toward the left calls, toward the right does not. */
    @Test
    fun tSearch25_rtlIsMirrored() {
        val h = harness()
        show(h, "zed", LayoutDirection.Rtl)
        dragRow(140.dp)
        assertThat(calls(h)).isEmpty()
        dragRow((-140).dp, startFraction = 0.8f)
        assertThat(calls(h)).containsExactly("+1 555 0100" to CallMode.DIAL)
    }

    /** T-SEARCH-25: OFF: no drag and no "Call" action; a tap still opens the contact. */
    @Test
    fun tSearch25_offHasNoSwipeAndNoAction() {
        val h = harness(SwipeToCall.OFF)
        show(h, "zed")
        assertThat(customActionLabels()).isEmpty()
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput {
            down(Offset(width * 0.3f, centerY))
            moveBy(Offset(viewConfiguration.touchSlop + 140.dp.toPx(), 0f))
        }
        compose.waitForIdle()
        // The row does not follow the finger: nothing is uncovered behind it.
        assertThat(compose.onAllNodesWithTag(SearchTestTags.CALL_HINT).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput { up() }
        compose.waitForIdle()
        assertThat(calls(h)).isEmpty()
        assertThat(h.contacts!!.numberQueries).isEmpty()
    }

    /** T-SEARCH-25: the accessibility action "Call <name>" calls like the swipe (FR-A11Y-01). */
    @Test
    fun tSearch25_accessibilityActionCalls() {
        val h = harness()
        show(h, "zed")
        assertThat(customActionLabels()).containsExactly("Call Zed Fixture")
        val action =
            compose
                .onNodeWithTag(SearchTestTags.contact(0))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
        assertThat(calls(h)).containsExactly("+1 555 0100" to CallMode.DIAL)
    }

    /** T-SEARCH-24 on screen: two numbers without a default: the chooser lists both; a tap calls that number. */
    @Test
    fun tSearch24_chooserOnScreen() {
        val h = harness()
        show(h, "zoe")
        dragRow(140.dp)
        compose.onNodeWithTag(SearchTestTags.NUMBER_CHOOSER).assertExists()
        compose.onNodeWithTag(SearchTestTags.number(0)).assertExists()
        compose.onNodeWithTag(SearchTestTags.number(1)).performClick()
        compose.waitForIdle()
        assertThat(calls(h)).containsExactly("+1 555 0121" to CallMode.DIAL)
        assertThat(compose.onAllNodesWithTag(SearchTestTags.NUMBER_CHOOSER).fetchSemanticsNodes()).isEmpty()
    }

    /** T-SEARCH-24 on screen: Cancel closes the chooser and keeps the results. */
    @Test
    fun tSearch24_chooserCancel() {
        val h = harness()
        show(h, "zoe")
        dragRow(140.dp)
        compose.onNodeWithTag(SearchTestTags.NUMBER_CANCEL).performClick()
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag(SearchTestTags.NUMBER_CHOOSER).fetchSemanticsNodes()).isEmpty()
        assertThat(calls(h)).isEmpty()
        compose.onNodeWithTag(SearchTestTags.contact(0)).assertExists()
    }

    /**
     * T-SEARCH-24 (review fix): the chooser is modal. The rows behind it are hidden from accessibility services, a tap
     * on the scrim over a contact row cancels instead of opening the contact, and a tap on the pane's own empty space
     * keeps it open.
     */
    @Test
    fun tSearch24_chooserIsModal() {
        val h = harness()
        show(h, "zoe")
        val rowCentre =
            compose
                .onNodeWithTag(SearchTestTags.contact(0))
                .fetchSemanticsNode()
                .boundsInRoot.center
        dragRow(140.dp)
        compose.onNodeWithTag(SearchTestTags.NUMBER_CHOOSER).assertExists()
        assertThat(compose.onAllNodesWithTag(SearchTestTags.contact(0)).fetchSemanticsNodes()).isEmpty()
        // The pane's title area is not a control: a tap there changes nothing.
        compose.onNodeWithTag(SearchTestTags.NUMBER_CHOOSER).performTouchInput { click(Offset(centerX, 4.dp.toPx())) }
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTestTags.NUMBER_CHOOSER).assertExists()
        // A tap where the contact row lies behind the scrim cancels; the contact is not opened.
        compose.onRoot().performTouchInput { click(rowCentre) }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag(SearchTestTags.NUMBER_CHOOSER).fetchSemanticsNodes()).isEmpty()
        assertThat(h.contacts!!.opened).isEmpty()
        assertThat(calls(h)).isEmpty()
        compose.onNodeWithTag(SearchTestTags.contact(0)).assertExists()
    }

    /**
     * T-SEARCH-25 (review fix, INTERACTION_SPEC §4.2 rule 1): a drag that goes down inside the left `systemGestures`
     * inset belongs to the system back gesture: the row does not move and nothing is called. The same drag started
     * outside the inset calls (positive control).
     */
    @Test
    fun tSearch25_systemGestureEdgeWins() {
        val h = harness()
        show(h, "zed")
        val edge = 32
        compose.runOnIdle {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(edge, 0, edge, 0))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
        assertThat(seenEdgeLeft).isEqualTo(edge)
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput {
            val start = Offset(edge / 2f, centerY)
            down(start)
            moveBy(Offset(viewConfiguration.touchSlop + 140.dp.toPx(), 0f))
        }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithTag(SearchTestTags.CALL_HINT).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithTag(SearchTestTags.contact(0)).performTouchInput { up() }
        compose.waitForIdle()
        assertThat(calls(h)).isEmpty()
        dragRow(140.dp)
        assertThat(calls(h)).containsExactly("+1 555 0100" to CallMode.DIAL)
    }

    /**
     * T-SEARCH-24 (review fix): on a short screen with the keyboard's inset up, the chooser for a contact with 8 numbers
     * fits above the inset; the title and Cancel stay on screen, and the list scrolls so the last number is reachable.
     */
    @Test
    @Config(qualifiers = "w411dp-h560dp")
    fun tSearch24_chooserFitsAShortViewportWithTheKeyboard() {
        val many = (0 until 8).map { PhoneNumber("+1 555 01" + (30 + it), "Mobile") }
        val zia = ContactHit("content://com.android.contacts/contacts/lookup/fx2/2", "Zia Fixture")
        val h =
            SearchHarness(
                settings = Settings(onboardingDone = true, contactsSearchEnabled = true),
                contacts =
                    FakeContactsSource(
                        permissionState = PermissionState.GRANTED,
                        contacts = listOf(zia),
                        numbers = mapOf(zia.lookupUri to many),
                    ),
            )
        show(h, "zia")
        val imePx = with(compose.density) { 260.dp.roundToPx() }
        compose.runOnIdle {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
        dragRow(140.dp)
        compose.onNodeWithTag(SearchTestTags.NUMBER_CHOOSER).assertExists()
        val rootHeight =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .size.height
        val limit = (rootHeight - imePx).toFloat()
        // Without the fix the pane (title, 8 rows, Cancel) is taller than this room.
        assertThat(limit).isLessThan(with(compose.density) { 480.dp.toPx() })
        val pane = compose.onNodeWithTag(SearchTestTags.NUMBER_CHOOSER).fetchSemanticsNode().boundsInRoot
        assertThat(pane.bottom).isAtMost(limit)
        val cancel = compose.onNodeWithTag(SearchTestTags.NUMBER_CANCEL).fetchSemanticsNode().boundsInRoot
        assertThat(cancel.bottom).isAtMost(limit)
        assertThat(cancel.height).isGreaterThan(0f)
        compose.onNodeWithTag(SearchTestTags.number(7)).performScrollTo()
        compose.waitForIdle()
        val last = compose.onNodeWithTag(SearchTestTags.number(7)).fetchSemanticsNode().boundsInRoot
        assertThat(last.bottom).isAtMost(cancel.top + 1f)
        assertThat(last.top).isAtLeast(pane.top)
        compose.onNodeWithTag(SearchTestTags.number(7)).performClick()
        compose.waitForIdle()
        assertThat(calls(h)).containsExactly("+1 555 0137" to CallMode.DIAL)
    }

    private fun customActionLabels(): List<String> =
        compose
            .onNodeWithTag(SearchTestTags.contact(0))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .map { it.label }
}
