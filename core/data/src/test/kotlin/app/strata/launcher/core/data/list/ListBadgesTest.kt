package app.strata.launcher.core.data.list

import app.strata.launcher.core.model.BadgeKey
import app.strata.launcher.core.model.Catalog
import app.strata.launcher.core.platformapi.apps.AppCatalog
import app.strata.launcher.testing.fakes.FakeCollation
import app.strata.launcher.testing.fixtures.AppFixtures
import app.strata.launcher.testing.fixtures.AppFixtures.app
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** FR-NOTIF-01/02: counts reach every row of the package in that profile, and leave again; memory only. */
class ListBadgesTest {
    private class Catalogs(initial: Catalog) : AppCatalog {
        override val catalog = MutableStateFlow(initial)

        override fun requestReconcile() = Unit
    }

    @Test
    fun badgesFollowPackageAndProfile() =
        runBlocking<Unit> {
            val job = Job()
            val badges = MutableStateFlow<Map<BadgeKey, Int>>(emptyMap())
            val catalog =
                Catalog(
                    listOf(
                        app("Mail", pkg = "fixture.mail"),
                        app("Mail Compose", pkg = "fixture.mail", activity = "ComposeActivity"),
                        app("Mail", pkg = "fixture.mail", serial = AppFixtures.WORK),
                        app("Maps"),
                    ),
                    fromSnapshot = false,
                )
            val list =
                AssembledAppListSource(
                    catalog = Catalogs(catalog),
                    overrides = MutableStateFlow(emptyMap()),
                    locales = MutableStateFlow(listOf("en")),
                    collation = FakeCollation(),
                    defaultDispatcher = Dispatchers.Default,
                    scope = CoroutineScope(job + Dispatchers.Default),
                    badges = badges,
                ).appList
            withTimeout(5_000) { list.first { it.appCount == 4 } }
            badges.value = mapOf(BadgeKey("fixture.mail", AppFixtures.MAIN) to 2)
            val badged = withTimeout(5_000) { list.first { l -> l.allApps.any { it.badge > 0 } } }
            val byKey = badged.allApps.associate { (it.packageName to it.key.userSerial to it.systemLabel) to it.badge }
            assertThat(byKey[("fixture.mail" to AppFixtures.MAIN) to "Mail"]).isEqualTo(2)
            assertThat(byKey[("fixture.mail" to AppFixtures.MAIN) to "Mail Compose"]).isEqualTo(2)
            assertThat(byKey[("fixture.mail" to AppFixtures.WORK) to "Mail"]).isEqualTo(0)
            assertThat(badged.allApps.single { it.systemLabel == "Maps" }.badge).isEqualTo(0)

            badges.value = emptyMap() // listener disconnected or badges turned off
            val cleared = withTimeout(5_000) { list.first { l -> l.allApps.none { it.badge > 0 } } }
            assertThat(cleared.appCount).isEqualTo(4)
            job.cancel()
        }
}
