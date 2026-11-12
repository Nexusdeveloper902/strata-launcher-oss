// `.strata-backup.json` codec, validator, preview and restore transaction (ARCHITECTURE §2, §12). Pure Kotlin: the
// stores it writes are reached through `:core:platform-api` ports, bound in `:app`.
plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.json)
    api(projects.core.platformApi)
    // Embedded themes are validated by the theme pipeline (THEME_SYSTEM §8) and written through ThemeBackupStore.
    api(projects.core.theme)

    testImplementation(projects.testing.fakes)
    testImplementation(projects.testing.fixtures)
}
