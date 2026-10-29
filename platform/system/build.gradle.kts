plugins { alias(libs.plugins.strata.android.library) }

dependencies {
    api(projects.core.platformApi)
    implementation(libs.androidx.core.ktx)

    testImplementation(projects.testing.fixtures)
}

tasks.withType<Test>().configureEach {
    // `-Pstrata.recordGoldens=true` rewrites testing/fixtures/.../fixtures/sections/*.txt (T-LIST-03).
    systemProperty("strata.goldenDir", rootProject.file("testing/fixtures/src/main/resources/fixtures/sections").path)
    providers.gradleProperty("strata.recordGoldens").orNull?.let { systemProperty("strata.recordGoldens", it) }
}
