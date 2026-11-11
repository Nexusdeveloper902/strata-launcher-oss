package app.strata.launcher.feature.search

import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.testing.fakes.FakeAppCatalog
import app.strata.launcher.testing.fakes.FakeAppLauncher
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeClock
import app.strata.launcher.testing.fakes.FakeContactsSource
import app.strata.launcher.testing.fakes.FakeLaunchLogRepository
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fakes.FakeSystemIntents
import app.strata.launcher.testing.fakes.FakeTextFolding
import app.strata.launcher.testing.fixtures.SearchFixtures.visible
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Labels of the search tests, in collation order (the assembled list's order is the tie order). */
val LABELS =
    listOf("Calculator", "Calendar", "Calligraphy", "Camera", "Clock", "Hidden Diary", "Maps", "Notes", "Weather")

/** Fakes around one [SearchViewModel]. */
class SearchHarness(
    apps: List<VisibleApp> = LABELS.map { visible(it, hidden = it == "Hidden Diary") },
    settings: Settings = Settings(onboardingDone = true),
    storeHandler: Boolean = true,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    val contacts: FakeContactsSource? = null,
) {
    val clock = FakeClock()
    val appList = FakeAppListSource(listOf(apps))
    val settings = FakeSettingsRepository(settings)
    val launchLog = FakeLaunchLogRepository(clock)
    val launcher = FakeAppLauncher()
    val catalog = FakeAppCatalog()
    val intents = FakeSystemIntents(storeHandler)
    val viewModel =
        SearchViewModel(
            appList = appList,
            settings = this.settings,
            launchLog = launchLog,
            launcher = launcher,
            catalog = catalog,
            textFolding = FakeTextFolding(),
            systemIntents = intents,
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            contacts = contacts,
        )

    fun app(label: String): VisibleApp =
        appList.state.value.allApps
            .first { it.systemLabel == label }

    /** Records [label] launches, one second apart (newest last). */
    suspend fun launched(vararg labels: String) {
        for (l in labels) {
            clock.advanceBy(1_000)
            launchLog.record(app(l).key, ProfileKind.MAIN)
        }
    }
}

fun FakeAppListSource(apps: List<List<VisibleApp>>): FakeAppListSource {
    val flat = apps.flatten()
    val (hidden, shown) = flat.partition { it.hidden }
    return FakeAppListSource(
        AppList(
            sections = if (shown.isEmpty()) emptyList() else listOf(AppSection("A", shown)),
            index = emptyList(),
            hiddenCount = hidden.size,
            fromSnapshot = false,
            hiddenApps = hidden,
        ),
    )
}
