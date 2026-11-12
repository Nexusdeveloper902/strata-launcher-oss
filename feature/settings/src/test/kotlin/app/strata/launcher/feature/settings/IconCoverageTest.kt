package app.strata.launcher.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.model.IconTint
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeIconProvider
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** T-SET-09 (C + R): Settings -> Icons over a fake coverage of 10 apps, export, and the switch to tint `none`. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class IconCoverageTest {
    @get:Rule
    val compose = createComposeRule()

    private val sources =
        listOf(
            IconSource.OVERRIDE,
            IconSource.OVERRIDE,
            IconSource.PACK,
            IconSource.PACK,
            IconSource.GLYPH,
            IconSource.GLYPH,
            IconSource.PLATFORM_MONOCHROME,
            IconSource.DESATURATED,
            IconSource.DESATURATED,
            IconSource.DESATURATED,
        )
    private val apps = sources.indices.map { visible("App$it", pkg = "fixture.cov.app$it") }
    private val icons =
        FakeIconProvider().apply {
            apps.zip(sources).forEach { (a, s) ->
                coverageSources[a.key.component] =
                    s
            }
        }
    private val intents = FakeSystemIntents()
    private val appList =
        FakeAppListSource(AppList(listOf(AppSection("A", apps)), emptyList(), hiddenCount = 0, fromSnapshot = false))

    private fun theme(tint: IconTint): ResolvedTheme {
        val base = StrataDefault.resolved(systemDark = false)
        return base.copy(icons = base.icons.copy(tint = tint))
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun scrollTo(t: String) =
        compose
            .onNodeWithTag(
                SettingsPageTags.ICONS_PAGE,
            ).performScrollToNode(hasTestTag(t))

    @Test
    fun tSet09_coverageCountsHighlightsExportAndTintNone() {
        val vm = IconCoverageViewModel(appList, icons, intents, RecordingLogger())
        var tint by mutableStateOf(IconTint.MONOCHROME)
        compose.setContent {
            StrataTheme(theme(tint)) {
                val state by vm.state.collectAsStateWithLifecycle()
                IconCoveragePage(state, vm::onAction)
            }
        }
        compose.waitForIdle()
        assertThat(vm.state.value.loading).isFalse()
        // Counts 2/2/2/1/3 per source.
        val expected =
            mapOf(
                IconSource.OVERRIDE to 2,
                IconSource.PACK to 2,
                IconSource.GLYPH to 2,
                IconSource.PLATFORM_MONOCHROME to 1,
                IconSource.DESATURATED to 3,
            )
        assertThat(
            vm.state.value.counts
                .toMap(),
        ).isEqualTo(expected)
        for ((source, n) in expected) {
            tag(SettingsPageTags.count(source)).assertExists()
            assertThat(textOf(SettingsPageTags.count(source))).endsWith(": $n")
        }
        tag(SettingsPageTags.count(IconSource.PLAIN)).assertDoesNotExist()
        // Rows show label and source; the 3 desaturated rows are highlighted with a spoken marker.
        val entries = vm.state.value.entries
        assertThat(entries.map { it.label }).isEqualTo(apps.map { it.displayLabel })
        val desaturated = entries.filter { it.source == IconSource.DESATURATED }
        for (e in desaturated) {
            scrollTo(SettingsPageTags.highlighted(e))
            assertThat(
                descriptionOf(SettingsPageTags.highlighted(e)),
            ).isEqualTo("${e.label}, Desaturated fallback, Needs a glyph")
        }
        val glyph = entries.first { it.source == IconSource.GLYPH }
        scrollTo(SettingsPageTags.coverageRow(glyph))
        assertThat(descriptionOf(SettingsPageTags.coverageRow(glyph))).isEqualTo("${glyph.label}, Strata glyph")
        assertThat(compose.onAllNodesWithTag(SettingsPageTags.highlighted(glyph)).fetchSemanticsNodes()).isEmpty()

        // Export: ACTION_SEND text/plain (FakeSystemIntents.shareText) with 3 lines "Label (package)".
        scrollTo(SettingsPageTags.ICONS_EXPORT)
        tag(SettingsPageTags.ICONS_EXPORT).performClick()
        compose.waitForIdle()
        val (text, _) = intents.textShares.single()
        assertThat(
            text.lines(),
        ).containsExactly("App7 (fixture.cov.app7)", "App8 (fixture.cov.app8)", "App9 (fixture.cov.app9)")
            .inOrder()
        // All-apps toggle: 10 lines.
        tag(SettingsPageTags.ICONS_EXPORT_ALL).performClick()
        tag(SettingsPageTags.ICONS_EXPORT).performClick()
        compose.waitForIdle()
        assertThat(
            intents.textShares
                .last()
                .first
                .lines(),
        ).hasSize(10)

        // Tint none: note shown, no highlights, the 6 apps without override or pack are "System icon (untinted)".
        tint = IconTint.NONE
        compose.waitForIdle()
        assertThat(vm.state.value.tinted).isFalse()
        assertThat(
            vm.state.value.counts
                .toMap(),
        ).isEqualTo(mapOf(IconSource.OVERRIDE to 2, IconSource.PACK to 2, IconSource.PLAIN to 6))
        scrollTo(SettingsPageTags.ICONS_NOTE)
        assertThat(textOf(SettingsPageTags.ICONS_NOTE)).contains("doesn't tint icons")
        assertThat(textOf(SettingsPageTags.count(IconSource.PLAIN))).isEqualTo("System icon (untinted): 6")
        for (s in listOf(IconSource.GLYPH, IconSource.PLATFORM_MONOCHROME, IconSource.DESATURATED)) {
            tag(SettingsPageTags.count(s)).assertDoesNotExist()
        }
        for (e in vm.state.value.entries) {
            assertThat(compose.onAllNodesWithTag(SettingsPageTags.highlighted(e)).fetchSemanticsNodes()).isEmpty()
        }
        // The default export (desaturated only) is now empty, so the button is disabled; all apps still works.
        tag(SettingsPageTags.ICONS_EXPORT_ALL).performClick()
        tag(SettingsPageTags.ICONS_EXPORT).assertIsNotEnabled()
        tag(SettingsPageTags.ICONS_EXPORT_ALL).performClick()
        tag(SettingsPageTags.ICONS_EXPORT).assertIsEnabled()
        // Read-only: the coverage calls carried the theme's style only; nothing else was started.
        assertThat(icons.coverageCalls.map { it.tint.name }).containsAtLeast("MONOCHROME", "NONE").inOrder()
        assertThat(intents.started).isEmpty()
        assertThat(intents.shares).isEmpty()
    }

    @Test
    fun exportWithoutHandlerReportsNoHandler() {
        intents.missing += FakeSystemIntents.SHARE
        val vm = IconCoverageViewModel(appList, icons, intents, RecordingLogger())
        val effects = mutableListOf<SettingsEffect>()
        compose.setContent {
            StrataTheme(theme(IconTint.ACCENT)) {
                val state by vm.state.collectAsStateWithLifecycle()
                IconCoveragePage(state, vm::onAction)
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.effects.collect { effects += it } }
        }
        compose.waitForIdle()
        vm.onAction(IconCoverageAction.Export("t"))
        compose.waitForIdle()
        assertThat(effects).containsExactly(SettingsEffect.NoHandler)
    }

    private fun textOf(t: String): String =
        tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun descriptionOf(t: String): String =
        tag(t).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString("")
}
