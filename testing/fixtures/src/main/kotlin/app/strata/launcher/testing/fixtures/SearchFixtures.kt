package app.strata.launcher.testing.fixtures

import app.strata.launcher.core.model.AppEntry
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.VisibleApp

/** Search fixtures (TEST_PLAN T-SEARCH-04): `fixtures/search/labels.txt` and `ranking.csv`. */
object SearchFixtures {
    /** One row of the ranking table. [check] is `first`, `top:N`, `absent` or `none`. */
    data class RankingRow(val query: String, val check: String, val expected: String)

    /** The 60 labels of the ranking fixture. */
    val LABELS: List<String> by lazy { lines("fixtures/search/labels.txt") }

    val RANKING: List<RankingRow> by lazy {
        lines("fixtures/search/ranking.csv").map { line ->
            val parts = line.split(',')
            RankingRow(parts[0], parts[1], parts.getOrElse(2) { "" })
        }
    }

    /** A visible app for [label] ([custom] = renamed display label). */
    fun visible(
        label: String,
        custom: String? = null,
        hidden: Boolean = false,
        pkg: String = "fixture.search." + Integer.toHexString(label.hashCode()),
        serial: Long = AppFixtures.MAIN,
    ): VisibleApp =
        VisibleApp(
            key = AppKey("$pkg/$pkg.MainActivity", serial),
            packageName = pkg,
            displayLabel = custom ?: label,
            systemLabel = label,
            sectionKey = "",
            profileKind = if (serial == AppFixtures.MAIN) ProfileKind.MAIN else ProfileKind.WORK,
            hidden = hidden,
        )

    /** [AppFixtures] entries as visible apps (no overrides), for the 500-app index (T-PERF-05). */
    fun visibleOf(entries: List<AppEntry>): List<VisibleApp> =
        entries.map {
            VisibleApp(
                key = it.key,
                packageName = it.packageName,
                displayLabel = it.systemLabel,
                systemLabel = it.systemLabel,
                sectionKey = "",
                profileKind = it.profileKind,
                hidden = false,
            )
        }

    private fun lines(path: String): List<String> {
        val stream =
            SearchFixtures::class.java.classLoader.getResourceAsStream(path)
                ?: error("missing fixture $path")
        return stream
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
