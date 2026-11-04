package app.strata.launcher.feature.theme

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.data.list.AssembledAppListSource
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.IconPackInfo
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.IconResult
import app.strata.launcher.core.ui.StrataDefaultTheme
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fakes.FakeIconProvider
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.RecordingLogger
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** S-ICON (FR-APPS-06), level C: choose a pack icon, reset, unusable packs, the app's own mapping first. */
@RunWith(AndroidJUnit4::class)
class IconChooserTest {
    @get:Rule
    val compose = createComposeRule()

    private val maps = app("Maps")
    private val ok = "fixture.pack.ok"
    private val bad = "fixture.pack.bad"
    private val overrides = FakeOverridesRepository()
    private val icons =
        FakeIconProvider(
            IconPacks(
                listOf(IconPackInfo(ok, "OK Pack", 1, true, 3), IconPackInfo(bad, "Bad Pack", 1, false)),
                discovered = true,
            ),
        ).apply {
            drawables[ok] = listOf("alpha", "maps_icon", "zulu") to mapOf(maps.key.component to "maps_icon")
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val appList =
        AssembledAppListSource(
            catalog = FakeAppCatalog(Catalog(listOf(maps), fromSnapshot = false)),
            overrides = overrides.overrides,
            locales = MutableStateFlow(listOf("en")),
            collation = FakeCollation(),
            defaultDispatcher = Dispatchers.Unconfined,
            scope = scope,
        )

    private fun open(
        repo: app.strata.launcher.core.platformapi.apps.OverridesRepository = overrides,
    ): IconChooserViewModel {
        val vm = IconChooserViewModel(maps.key, appList, repo, icons, RecordingLogger())
        compose.setContent {
            StrataDefaultTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                IconChooserScreen(state, vm::onAction) { _, _, _, _ -> IconResult.Monogram }
            }
        }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun choosingAPackIconSavesTheOverride() {
        val vm = open()
        // The pack's own mapping for the app comes first.
        assertThat(
            vm.state.value.visibleDrawables
                .first(),
        ).isEqualTo("maps_icon")
        compose.onNodeWithTag(IconChooserTags.cell("zulu")).performClick()
        compose.waitForIdle()
        assertThat(overrides.state.value[maps.key]?.icon).isEqualTo(IconOverride(ok, "zulu"))
        assertThat(vm.state.value.done).isTrue()
    }

    @Test
    fun themeIconResets() {
        overrides.state.value =
            mapOf(
                maps.key to
                    app.strata.launcher.core.model
                        .AppOverride(maps.key, icon = IconOverride(ok, "alpha")),
            )
        val vm = open()
        compose.onNodeWithTag(IconChooserTags.THEME_ICON).performClick()
        compose.waitForIdle()
        assertThat(overrides.state.value[maps.key]).isNull()
        assertThat(vm.state.value.done).isTrue()
    }

    /** NFR-COMPAT-04: an unusable pack is listed but cannot be opened. */
    @Test
    fun unusablePackIsDisabled() {
        open()
        compose.onNodeWithTag(IconChooserTags.pack(bad)).assertIsNotEnabled()
    }

    @Test
    fun searchFilters() {
        val vm = open()
        compose.onNodeWithTag(IconChooserTags.SEARCH).performTextInput("zu")
        compose.waitForIdle()
        assertThat(vm.state.value.visibleDrawables).containsExactly("zulu")
    }

    @Test
    fun noPacksMessage() {
        icons.iconPacks.value = IconPacks(emptyList(), discovered = true)
        open()
        compose.onNodeWithTag(IconChooserTags.NO_PACKS).assertExists()
    }

    @Test
    fun writeFailureIsAMessageNotACrash() {
        val failing =
            object : app.strata.launcher.core.platformapi.apps.OverridesRepository by overrides {
                override suspend fun setIconOverride(
                    key: app.strata.launcher.core.model.AppKey,
                    profileKind: app.strata.launcher.core.model.ProfileKind,
                    icon: IconOverride?,
                ) = throw IllegalStateException("disk full")
            }
        val vm = open(failing)
        compose.onNodeWithTag(IconChooserTags.cell("alpha")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(IconChooserTags.WRITE_FAILED).assertExists()
        assertThat(vm.state.value.writeFailed).isTrue()
        assertThat(vm.state.value.done).isFalse()
    }
}
