package app.strata.launcher.core.model

/**
 * FB-019: Strata's own launcher entry moved from `settings.SettingsActivity` to the window-less `settings.EntryActivity`.
 * Everything stored per app (favourites, overrides: hidden, label, icon; the catalog cache; the launch log; backups)
 * is keyed by the flattened component, so keys of the old entry are rewritten to the new one: once in the databases
 * when they open, and in every backup that is restored. Only Strata's own package is touched. Pure.
 */
object EntryKeyMigration {
    const val OLD_CLASS = "app.strata.launcher.settings.SettingsActivity"
    const val NEW_CLASS = "app.strata.launcher.settings.EntryActivity"

    /** The old entry's component in its flattened forms (`ComponentName.flattenToString` and the short form). */
    fun legacyComponents(ownPackage: String): List<String> =
        listOf("$ownPackage/$OLD_CLASS", "$ownPackage/.settings.SettingsActivity")

    fun currentComponent(ownPackage: String): String = "$ownPackage/$NEW_CLASS"

    /** [component] with the old entry replaced by the new one; anything else unchanged. */
    fun component(
        component: String,
        ownPackage: String,
    ): String = if (component in legacyComponents(ownPackage)) currentComponent(ownPackage) else component

    fun key(
        key: AppKey,
        ownPackage: String,
    ): AppKey = component(key.component, ownPackage).let { if (it == key.component) key else key.copy(component = it) }
}
