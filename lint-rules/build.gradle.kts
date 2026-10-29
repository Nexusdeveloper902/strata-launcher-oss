// Custom Android Lint rules (ARCHITECTURE 15/16, T-CI-04). Lint loads the jar with its own Kotlin runtime,
// so the stdlib stays off this module's runtime classpath.
plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    compileOnly(libs.android.lint.api)
    compileOnly(libs.android.lint.checks)
    testImplementation(libs.android.lint.api)
    testImplementation(libs.android.lint.checks)
    testImplementation(libs.android.lint.tests)
}

tasks.jar {
    manifest {
        attributes("Lint-Registry-v2" to "app.strata.launcher.lint.StrataIssueRegistry")
    }
}
