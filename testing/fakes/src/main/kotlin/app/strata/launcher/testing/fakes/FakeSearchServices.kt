package app.strata.launcher.testing.fakes

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.platformapi.apps.LaunchError
import app.strata.launcher.core.platformapi.apps.LaunchResult
import app.strata.launcher.core.platformapi.system.SystemIntents
import app.strata.launcher.core.platformapi.system.TextFolding

/**
 * [TextFolding] from a fixed table (pure JVM; no ICU on this module's runtime classpath, T-CI-02).
 * Tests that need real ICU4J transliteration use the test-only ICU adapter in `:core:search` tests.
 */
class FakeTextFolding(
    private val table: Map<String, String> = emptyMap(),
) : TextFolding {
    val calls = mutableListOf<String>()

    override fun transliterate(s: String): String? {
        calls += s
        return table[s]
    }
}

/** Records hand-offs; [storeHandler] = whether a `market:` activity resolves (T-SEARCH-15). */
class FakeSystemIntents(
    var storeHandler: Boolean = true,
) : SystemIntents {
    val storeSearches = mutableListOf<String>()

    override fun canSearchStore(): Boolean = storeHandler

    override fun storeSearch(query: String): LaunchResult {
        if (!storeHandler) return Outcome.Err(LaunchError.NOT_FOUND)
        storeSearches += query
        return Outcome.Ok(Unit)
    }
}
