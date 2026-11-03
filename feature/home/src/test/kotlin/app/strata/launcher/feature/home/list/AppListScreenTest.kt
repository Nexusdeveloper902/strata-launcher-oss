package app.strata.launcher.feature.home.list

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.list.AppListAssembler
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.system.HapticKind
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.ui.AlphabetIndexTestTags
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.feature.home.HomeAction
import app.strata.launcher.feature.home.HomeViewModel
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fakes.FakeHaptics
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fixtures.AppFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S-LIST Compose tests on Robolectric (level C). Screenshot halves (S) need Roborazzi (not set up yet). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1400dp")
class AppListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val collation = FakeCollation()
    private val haptics = FakeHaptics()
    private val settings = FakeSettingsRepository(Settings(onboardingDone = true))
    private val scrubs = mutableListOf<Boolean>()
    private val launched = mutableListOf<AppKey>()
    private var settingsOpened = 0
    private var hiddenOpened = 0

    private fun assemble(
        apps: List<AppEntry>,
        overrides: Map<AppKey, AppOverride> = emptyMap(),
    ): AppList =
        AppListAssembler.assemble(
            Catalog(apps, fromSnapshot = false),
            overrides,
            collation.sections(listOf("en")),
            collation.comparator(listOf("en")),
        )

    private fun viewModel() =
        HomeViewModel(
            FakeRoleController(),
            settings,
            FakeAppListSource(),
            FakeAppCatalog(),
            FakeAppLauncher(),
            FakeLaunchLogRepository(),
            haptics,
            FakeResetNotices(),
            Dispatchers.Unconfined,
            app.strata.launcher.testing.fakes
                .FakeOverridesRepository(),
            app.strata.launcher.testing.fakes
                .FakeFavoritesRepository(),
            app.strata.launcher.testing.fakes
                .FakeSystemIntents(),
            app.strata.launcher.testing.fakes
                .FakeLockService(),
            app.strata.launcher.testing.fakes
                .RecordingLogger(),
        )

    private fun show(
        list: AppList,
        twoColumns: Boolean = true,
        listState: LazyListState = LazyListState(),
        onScrub: (Boolean) -> Unit = { scrubs += it },
    ) {
        compose.setContent {
            StrataTheme(StrataDefault.resolved(systemDark = false)) {
                AppListScreen(
                    list = list,
                    twoColumnSetting = twoColumns,
                    badgeStyle = BadgeStyle.DOT,
                    onLaunch = { app, _ -> launched += app.key },
                    onIndexScrub = onScrub,
                    onOpenSettings = { settingsOpened++ },
                    onOpenHiddenApps = { hiddenOpened++ },
                    listState = listState,
                )
            }
        }
    }

    private fun centerOf(tag: String): Offset {
        val b = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
        val index = compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).getBoundsInRoot()
        return with(compose.density) {
            Offset(index.width.toPx() / 2f, ((b.top + b.height / 2) - index.top).toPx())
        }
    }

    /** Drags the finger over every letter from A to Z; returns the haptic ticks. */
    private fun scrubAtoZ() {
        val points = ('A'..'Z').map { centerOf(AlphabetIndexTestTags.letter(it.toString())) }
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).performTouchInput {
            down(points.first())
            points.drop(1).forEach { moveTo(it) }
        }
        bubble().assert(hasAnyDescendant(hasText("Z")))
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).performTouchInput { up() }
        compose.onNodeWithTag(AlphabetIndexTestTags.BUBBLE, useUnmergedTree = true).assertDoesNotExist()
    }

    /** T-LIST-04: A→Z gives 25 section changes and 25 ticks; 0 ticks with the haptics master off. */
    @Test
    fun tList04_scrubJumpsAndTicks() {
        val list = assemble(AppFixtures.APPS500)
        assertThat(list.sections.count { it.label.length == 1 && it.label[0] in 'A'..'Z' }).isEqualTo(26)
        val vm = viewModel()
        val state = LazyListState()
        show(list, listState = state, onScrub = { first ->
            scrubs += first
            vm.onAction(HomeAction.IndexScrub(first))
        })
        scrubAtoZ()
        assertThat(scrubs.count { !it }).isEqualTo(25)
        assertThat(haptics.count(HapticKind.TICK)).isEqualTo(25)
        compose.onNodeWithTag(AppListTestTags.header("Z")).assertIsDisplayed()

        scrubs.clear()
        settings.set(Settings(onboardingDone = true, hapticsEnabled = false))
        compose.runOnIdle { }
        scrubAtoZ()
        assertThat(scrubs.count { !it }).isEqualTo(25)
        assertThat(haptics.count(HapticKind.TICK)).isEqualTo(25) // unchanged: 0 new ticks
    }

    /** T-LIST-05 (C half): an empty letter is not a button and snaps to the next section. */
    @Test
    fun tList05_emptyLetterSnapsToNext() {
        val list = assemble(AppFixtures.APPS_NO_Q)
        assertThat(list.index.first { it.label == "Q" }.isEmpty).isTrue()
        val state = LazyListState()
        show(list, listState = state)
        compose
            .onNodeWithTag(AlphabetIndexTestTags.letter("Q"), useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose
            .onNodeWithTag(AlphabetIndexTestTags.letter("R"), useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        val q = centerOf(AlphabetIndexTestTags.letter("Q"))
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).performTouchInput { down(q) }
        bubble().assert(hasAnyDescendant(hasText("R"))) // the bubble shows the target section
        compose.onNodeWithTag(AlphabetIndexTestTags.INDEX).performTouchInput { up() }
        val rStart =
            ListItems.build(list, twoColumns = false, headers = true).sectionStarts[
                list.index
                    .first {
                        it.label ==
                            "R"
                    }.sectionIndex,
            ]
        compose.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(rStart) }
        assertThat(scrubs).containsExactly(true)
    }

    /** T-LIST-09: list end rows with the hidden count; each opens its screen. */
    @Test
    fun tList09_listEndRows() {
        val apps = AppFixtures.APPS120
        val hidden = apps.take(3).associate { it.key to AppOverride(it.key, hidden = true) }
        show(assemble(apps, hidden))
        scrollable().performScrollToNode(hasTestTag(AppListTestTags.END_HIDDEN))
        compose.onNodeWithText("Hidden apps (3)").assertIsDisplayed().performClick()
        compose.onNodeWithTag(AppListTestTags.END_SETTINGS).performClick()
        compose.runOnIdle {
            assertThat(hiddenOpened).isEqualTo(1)
            assertThat(settingsOpened).isEqualTo(1)
        }
    }

    /** T-LIST-11: setting off → one column even on a wide window. */
    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun tList11_twoColumnOff_oneColumn() {
        val list = assemble(AppFixtures.APPS120)
        show(list, twoColumns = false)
        val a = list.sections.first { it.label == "A" }.apps
        val first = compose.onNodeWithTag(AppListTestTags.row(a[0])).getBoundsInRoot()
        val second = compose.onNodeWithTag(AppListTestTags.row(a[1])).getBoundsInRoot()
        assertThat(second.top).isGreaterThan(first.top)
        assertThat(second.left).isEqualTo(first.left)
    }

    /** T-LIST-10 (C half): ≥ 600 dp and the default setting → two columns, column-major inside a section. */
    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun tList10_twoColumnsColumnMajor() {
        val list = assemble(AppFixtures.APPS120)
        show(list, twoColumns = true)
        val a = list.sections.first { it.label == "A" }.apps
        val half = (a.size + 1) / 2
        val first = compose.onNodeWithTag(AppListTestTags.row(a[0])).getBoundsInRoot()
        val endCol = compose.onNodeWithTag(AppListTestTags.row(a[half])).getBoundsInRoot()
        val second = compose.onNodeWithTag(AppListTestTags.row(a[1])).getBoundsInRoot()
        assertThat(endCol.top).isEqualTo(first.top)
        assertThat(endCol.left).isGreaterThan(first.left)
        assertThat(second.left).isEqualTo(first.left)
        assertThat(second.top).isGreaterThan(first.top)
        // Section-major: every A row sits above the B header.
        val b = compose.onNodeWithTag(AppListTestTags.header("B")).getBoundsInRoot()
        a.forEach {
            assertThat(
                compose.onNodeWithTag(AppListTestTags.row(it)).getBoundsInRoot().bottom,
            ).isAtMost(b.top)
        }
    }

    /** T-APPS-17 (C half): duplicate rows carry distinct secondary lines, and the spoken text differs. */
    @Test
    fun tApps17_duplicateRowsDistinct() {
        val list = assemble(AppFixtures.APPS500)
        show(list)
        val cameras = list.sections.flatMap { it.apps }.filter { it.displayLabel == "Camera" }
        scrollable().performScrollToNode(hasTestTag(AppListTestTags.row(cameras[0])))
        val texts =
            cameras.map { app ->
                compose
                    .onNodeWithTag(AppListTestTags.row(app))
                    .fetchSemanticsNode()
                    .config[
                    androidx.compose.ui.semantics.SemanticsProperties.Text,
                ].joinToString { it.text }
            }
        assertThat(texts).containsExactly("Camera, Photo", "Camera, Video")
        val calculators = list.sections.flatMap { it.apps }.filter { it.displayLabel == "Calculator" }
        assertThat(calculators).hasSize(2)
        scrollable().performScrollToNode(hasTestTag(AppListTestTags.row(calculators[1])))
        compose.onNodeWithText("Work", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Personal", useUnmergedTree = true).assertExists()
    }

    /** FR-APPS-02 (C half): tapping a row launches it. */
    @Test
    fun tapLaunches() {
        val list = assemble(AppFixtures.APPS120)
        show(list)
        val first =
            list.sections
                .first()
                .apps
                .first()
        compose.onNodeWithTag(AppListTestTags.row(first)).performClick()
        compose.runOnIdle { assertThat(launched).containsExactly(first.key) }
    }

    private fun bubble() = compose.onNodeWithTag(AlphabetIndexTestTags.BUBBLE, useUnmergedTree = true)

    private fun scrollable() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
}
