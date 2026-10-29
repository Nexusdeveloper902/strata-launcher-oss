package app.strata.launcher.core.theme.parse

import app.strata.launcher.core.json.JsonNumber
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.theme.model.ThemeSchema

/** One step `migrate_N_to_N+1` of THEME_SYSTEM §10.6: a pure function on the raw tree that keeps unknown keys. */
interface ThemeMigration {
    val fromVersion: Int

    fun migrate(tree: JsonObject): JsonObject
}

/**
 * The migration chain. v1 ships it empty (no predecessors), but the mechanism is live so v2 is not
 * a special case. [targetVersion] is the app's major; tests may build chains to other targets.
 */
class ThemeMigrations(
    steps: List<ThemeMigration>,
    val targetVersion: Int = ThemeSchema.MAJOR,
) {
    private val byFrom: Map<Int, ThemeMigration> = steps.associateBy { it.fromVersion }

    init {
        require(byFrom.size == steps.size) { "one migration per source version" }
        val sorted = steps.map { it.fromVersion }.sorted()
        require(sorted.isEmpty() || sorted.last() == targetVersion - 1) { "chain must end at the target version" }
        require(sorted.zipWithNext().all { (a, b) -> b == a + 1 }) { "chain must be contiguous" }
    }

    /** Oldest `schemaVersion` this chain can load. */
    val oldestSupported: Int = steps.minOfOrNull { it.fromVersion } ?: targetVersion

    /**
     * Applies every step from [fromVersion] up to [targetVersion], setting `schemaVersion` after each.
     * Throws only if a step throws; the parser turns that into [IssueCode.MIGRATION_FAILED].
     */
    fun migrate(
        tree: JsonObject,
        fromVersion: Int,
    ): JsonObject {
        require(fromVersion in oldestSupported..targetVersion)
        var t = tree
        for (v in fromVersion until targetVersion) {
            t = byFrom.getValue(v).migrate(t).with("schemaVersion", JsonNumber.of(v + 1))
        }
        return t
    }

    companion object {
        /** The production chain of schema 1: empty. */
        val V1 = ThemeMigrations(emptyList())
    }
}
