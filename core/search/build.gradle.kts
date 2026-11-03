plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    testImplementation(projects.testing.fixtures)
    // ICU4J is test-only (ARCHITECTURE §15): the JVM stand-in for android.icu transliteration (T-SEARCH-04).
    testImplementation(libs.icu4j)
}
