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
import app.strata.launcher.core.model.IconPacks
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.theme.StrataDefault
import app.strata.launcher.core.theme.resolve.ResolvedTheme
import app.strata.launcher.core.theme.store.ThemeRepository
import app.strata.launcher.core.ui.StrataTheme
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeBadgeSource
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeHaptics
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeLockService
import app.strata.launcher.testing.fakes.FakeOverridesRepository
import app.strata.launcher.testing.fakes.FakeProfileSource
import app.strata.launcher.testing.fakes.FakeResetNotices
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeShortcutSource
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
    themeRepository: ThemeRepository? = null,
    /** Installed icon packs (FR-APPS-06 notices). */
    val iconPacks: MutableStateFlow<IconPacks> = MutableStateFlow(IconPacks(discovered = true)),
    val role: FakeRoleController = FakeRoleController(defaultHome = true),
    /** Work profile and private space (FR-APPS-09/10). */
    val profiles: FakeProfileSource = FakeProfileSource(),
    /** App shortcuts (FR-APPS-08); null = none wired. */
    val shortcuts: FakeShortcutSource? = null,
    /** Notification badges (FR-NOTIF-01/02); null = none wired. */
    val badges: FakeBadgeSource? = null,
) {
    /** Taps on the APP_MODE banner's "Set as default". */
    var setDefaultTaps = 0

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
            profiles = profiles.profiles,
            badges = badges?.badges,
        )

    val vm =
        HomeViewModel(
            roleController = role,
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
            theme = themeRepository,
            iconPacks = iconPacks,
            profiles = profiles,
            shortcuts = shortcuts,
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

    /** The routed home without a theme (callers wrap it in their own [StrataTheme]). */
    @Composable
    fun Home() {
        val s by vm.state.collectAsStateWithLifecycle()
        HomeRoute(
            state = s,
            effects = vm.effects,
            onAction = vm::onAction,
            onNavigate = { navigations += it },
            showSetDefaultButton = true,
            onSetDefault = { setDefaultTaps++ },
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
