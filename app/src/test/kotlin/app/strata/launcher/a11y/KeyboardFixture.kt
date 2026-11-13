package app.strata.launcher.a11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.strata.launcher.TestAppGraph
import app.strata.launcher.core.model.AppList
import app.strata.launcher.core.model.AppSection
import app.strata.launcher.core.model.IndexLetter
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.model.VisibleApp
import app.strata.launcher.home.HomeActivity
import app.strata.launcher.testing.fakes.FakeAppListSource
import app.strata.launcher.testing.fakes.FakeFavoritesRepository
import app.strata.launcher.testing.fakes.FakeRoleController
import app.strata.launcher.testing.fakes.FakeSettingsRepository
import app.strata.launcher.testing.fixtures.AppFixtures

/** A small onboarded setup for the accessibility walks: two sections, one favourite. */
object KeyboardFixture {
    fun visible(label: String): VisibleApp =
        AppFixtures.app(label, pkg = "fixture.${label.lowercase().replace(' ', '_')}").let {
            VisibleApp(
                it.key,
                it.packageName,
                it.systemLabel,
                it.systemLabel,
                label.take(1).uppercase(),
                it.profileKind,
                hidden = false,
            )
        }

    val alpha = visible("Alpha Notes")
    val maps = visible("Maps")

    val list =
        AppList(
            sections = listOf(AppSection("A", listOf(alpha)), AppSection("M", listOf(maps))),
            index = listOf(IndexLetter("A", 0, 1), IndexLetter("M", 1, 1)),
            hiddenCount = 1,
            fromSnapshot = false,
        )

    fun graph(
        onboarded: Boolean = true,
        settings: Settings = Settings(onboardingDone = onboarded),
    ) = TestAppGraph(
        roleController = FakeRoleController(defaultHome = true),
        settingsRepository = FakeSettingsRepository(settings),
        appList = FakeAppListSource(list),
        favorites = FakeFavoritesRepository(listOf(maps.key)),
    )

    fun homeIntent(context: Context): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(context, HomeActivity::class.java))
}
