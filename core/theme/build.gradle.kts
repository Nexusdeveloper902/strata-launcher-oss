plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    api(projects.core.model)
    // Public API exposes Outcome (common) and the JSON tree of ParsedTheme (json).
    api(projects.core.common)
    api(projects.core.json)

    testImplementation(projects.testing.fixtures)
    testImplementation(projects.testing.fakes)
}
