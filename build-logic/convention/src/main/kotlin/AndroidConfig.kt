import com.android.build.api.dsl.Lint
import com.android.build.api.dsl.TestOptions
import org.gradle.api.Project

/**
 * Lint policy shared by every Android module. T-CI-04 (NFR-I18N-01): externalised-string checks
 * are errors. The custom `StringConcatInUi` rule comes from `:lint-rules` (wired as `lintChecks`).
 */
internal fun Lint.configureStrataLint() {
    abortOnError = true
    warningsAsErrors = false
    checkReleaseBuilds = false
    error.addAll(listOf("HardcodedText", "SetTextI18n", "PluralsCandidate"))
    // Versions are capped on purpose at releases that allow compileSdk 36 (see libs.versions.toml).
    disable.addAll(listOf("GradleDependency", "NewerVersionAvailable"))
}

internal fun TestOptions.configureStrataUnitTests(project: Project) {
    unitTests.isIncludeAndroidResources = true
    unitTests.isReturnDefaultValues = false
    // Optional local mirror for Robolectric's android-all jars (e.g. when Maven Central rate-limits).
    val robolectricRepo = project.providers.gradleProperty("strata.robolectric.repoUrl").orNull
    unitTests.all { test ->
        // Robolectric on SDK 36 reaches into FileDescriptor internals (JDK 17+ module rules).
        test.jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )
        if (robolectricRepo != null) test.systemProperty("robolectric.dependency.repo.url", robolectricRepo)
    }
}

internal fun Project.addAndroidTestDependencies() {
    addCommonTestDependencies()
    dependencies.add("testImplementation", libs.lib("robolectric"))
    dependencies.add("testImplementation", libs.lib("androidx-test-core"))
    dependencies.add("testImplementation", libs.lib("androidx-test-ext-junit"))
}

/**
 * Gradle Managed Devices for the per-PR smoke matrix (ARCHITECTURE 14.3: API 26, 34, 36 phones, x86_64) and the
 * AndroidX Test Orchestrator (each test in its own process, state cleared).
 * All three use the regular Google APIs image: ATD images ship without a soft keyboard, which T-SEARCH-10 needs.
 */
internal fun TestOptions.configureStrataManagedDevices() {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
    managedDevices.localDevices.apply {
        create("api26") {
            device = "Pixel 6"
            apiLevel = 26
            systemImageSource = "google"
        }
        create("api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "google"
        }
        create("api36") {
            device = "Pixel 6"
            apiLevel = 36
            systemImageSource = "google"
        }
    }
}

internal fun Project.addInstrumentedTestDependencies() {
    dependencies.add("androidTestImplementation", libs.lib("androidx-test-runner"))
    dependencies.add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
    dependencies.add("androidTestImplementation", libs.lib("androidx-test-core"))
    dependencies.add("androidTestImplementation", libs.lib("truth"))
    dependencies.add("androidTestUtil", libs.lib("androidx-test-orchestrator"))
}
