package app.strata.launcher.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strata.launcher.core.data.list.AssembledAppListSource
import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeHaptics
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeLockService
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.RecordingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Tag of the stand-in search layer the harness puts in the `searchLayer` slot. */
const val SEARCH_STUB = "search_stub"

/**
 * Home over fakes plus the real [AssembledAppListSource] (overrides and favourites flow through the same pipeline as
 * in the app). Robolectric Compose, level C.
 */
class HomeHarness(
    apps: List<AppEntry>,
    favorites: List<AppKey> = emptyList(),
    settings: Settings = Settings(onboardingDone = true),
    lockEnabled: Boolean = false,
) {
    val catalog = FakeAppCatalog(Catalog(apps, fromSnapshot = false))
    val overrides = FakeOverridesRepository()
    val favoritesRepo = FakeFavoritesRepository(favorites)
    val settingsRepo = FakeSettingsRepository(settings)
    val launcher = FakeAppLauncher()
    val launchLog = FakeLaunchLogRepository()
    val haptics = FakeHaptics()
    val systemIntents = FakeSystemIntents()
    val lockService = FakeLockService(enabled = lockEnabled)
    val navigations = mutableListOf<HomeEffect>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    val appList =
        AssembledAppListSource(
            catalog = catalog,
            overrides = overrides.overrides,
            locales = MutableStateFlow(listOf("en")),
            collation = FakeCollation(),
            defaultDispatcher = Dispatchers.Unconfined,
            scope = scope,
        )

    val vm =
        HomeViewModel(
            roleController = FakeRoleController(defaultHome = true),
            settings = settingsRepo,
            appList = appList,
            catalog = catalog,
            launcher = launcher,
            launchLog = launchLog,
            haptics = haptics,
            resetNotices = FakeResetNotices(),
            ioDispatcher = Dispatchers.Unconfined,
            overrides = overrides,
            favorites = favoritesRepo,
            systemIntents = systemIntents,
            lockService = lockService,
            logger = RecordingLogger(),
        )

    val state: HomeUiState get() = vm.state.value

    fun setContent(
        compose: ComposeContentTestRule,
        theme: ResolvedTheme = StrataDefault.resolved(systemDark = false),
        fontScale: Float? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            val scaled = if (fontScale != null) Density(density.density, fontScale) else density
            CompositionLocalProvider(LocalDensity provides scaled) {
                StrataTheme(theme) { Home() }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Home() {
        val s by vm.state.collectAsStateWithLifecycle()
        HomeRoute(
            state = s,
            effects = vm.effects,
            onAction = vm::onAction,
            onNavigate = { navigations += it },
            showSetDefaultButton = false,
            onSetDefault = {},
            searchLayer = { Box(Modifier.fillMaxSize().testTag(SEARCH_STUB)) },
        )
    }
}

/** Invokes the custom accessibility action labelled [label] on this node. */
fun SemanticsNodeInteraction.invokeCustomAction(
    compose: ComposeContentTestRule,
    label: String,
) {
    val action =
        fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
    compose.runOnIdle { action.action() }
    compose.waitForIdle()
}

/** Labels of the custom accessibility actions on this node. */
fun SemanticsNodeInteraction.customActionLabels(): List<String> =
    fetchSemanticsNode()
        .config
        .getOrElse(SemanticsActions.CustomActions) { emptyList() }
        .map { it.label }
