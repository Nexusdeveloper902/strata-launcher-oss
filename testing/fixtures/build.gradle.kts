// Fixtures authored by us (ARCHITECTURE §16, TEST_PLAN §0 "THEMES"). No third-party assets.
plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    implementation(projects.core.model)
}

// THEMES fixture: the spec's sample themes are the single source; they are copied onto the classpath
// as `themes/samples/*.strata-theme.json` so no test depends on a path relative to the repo root.
val syncSampleThemes by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("spec/sample-themes")) { include("*.strata-theme.json") }
    into(layout.buildDirectory.dir("generated/fixtures/themes/samples"))
}

sourceSets.main {
    resources.srcDir(syncSampleThemes.map { it.destinationDir.parentFile.parentFile })
}
