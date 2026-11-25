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

// Presets of the private overlay (CONTRIBUTING "Public mirror and CI"), when present, as `themes/overlay/`: the tests
// over the APK's `assets/themes/` expect the samples plus these. Without the overlay the directory is empty.
val overlayFixtures = layout.buildDirectory.dir("generated/fixtures/themes/overlay")
val syncOverlayThemes by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("themes-private/presets")) { include("*.strata-theme.json") }
    into(overlayFixtures)
    // A classpath directory cannot be listed portably: `index.txt` names the files (empty without the overlay).
    doLast {
        val dir = outputs.files.singleFile
        val names =
            dir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .filter { it.endsWith(".strata-theme.json") }
                .sorted()
        dir.mkdirs()
        dir.resolve("index.txt").writeText(names.joinToString("") { it + "\n" })
    }
}

sourceSets.main {
    resources.srcDir(syncSampleThemes.map { it.destinationDir.parentFile.parentFile })
}

tasks.named("processResources") { dependsOn(syncOverlayThemes) }
