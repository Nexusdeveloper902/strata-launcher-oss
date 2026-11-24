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
        // The M8 accessibility and layout suites start many activities on native graphics: more heap than the 512 MiB
        // default, and a fresh test JVM every 100 classes so the per-SDK Robolectric environments do not pile up.
        test.maxHeapSize = "2g"
        test.forkEvery = 100
    }
}

internal fun Project.addAndroidTestDependencies() {
    addCommonTestDependencies()
    dependencies.add("testImplementation", libs.lib("robolectric"))
    dependencies.add("testImplementation", libs.lib("androidx-test-core"))
    dependencies.add("testImplementation", libs.lib("androidx-test-ext-junit"))
}

/** The rest of the T-COMPAT-01 API matrix (26, 34 and 36 run on every PR). */
internal val COMPAT_NIGHTLY_APIS = listOf(28, 29, 31, 33, 35)

/**
 * Nightly API levels that run on the AOSP image (no Google Play services). The API 29 Google APIs image's system_server
 * has a 16 MB heap, and a GMS config-update push made it run out of memory and crash ("INSTRUMENTATION_ABORTED: System
 * has crashed": nightly runs 45009343354 and 45159389326, OutOfMemoryError in ConfigUpdateInstallReceiver). The device
 * tests need no GMS. What they need from the image is there: nightly 45407127667 on this image ran tHome05 (the
 * ACTION_SHOW_ALARMS and calendar-time intents both found an app), the right-edge Back test and the system Home gesture
 * test (its launcher is gesture-capable) and passed all three, in the compat job and both navigation-mode jobs.
 */
internal val AOSP_IMAGE_APIS = setOf(29)

/**
 * Gradle Managed Devices for the per-PR smoke matrix (ARCHITECTURE 14.3: API 26, 34, 36 phones, x86_64) and the
 * AndroidX Test Orchestrator (each test in its own process; `clearPackageData` in the application plugin clears the
 * app's state before each test). Window and transition animations are off for every test, not only the classes that
 * use `DeviceStateRule`. All use the regular Google APIs image: ATD images ship without a soft keyboard, which
 * T-SEARCH-10 needs.
 */
internal fun TestOptions.configureStrataManagedDevices() {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
    animationsDisabled = true
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
        // T-COMPAT-01 nightly matrix (ARCHITECTURE 14.3; .github/workflows/nightly.yml). Not run per PR.
        for (api in COMPAT_NIGHTLY_APIS) {
            create("api$api") {
                device = "Pixel 6"
                apiLevel = api
                systemImageSource = if (api in AOSP_IMAGE_APIS) "aosp" else "google"
            }
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
