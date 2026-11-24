import com.android.build.api.dsl.Lint
import com.android.build.api.dsl.TestOptions
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider

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
    val robolectric = project.robolectricOfflineArgs()
    unitTests.all { test ->
        // Robolectric on SDK 36 reaches into FileDescriptor internals (JDK 17+ module rules).
        test.jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
        )
        test.dependsOn(robolectric.writeTask)
        test.jvmArgumentProviders.add(robolectric.args)
        // The M8 accessibility and layout suites start many activities on native graphics: more heap than the 512 MiB
        // default, and a fresh test JVM every 100 classes so the per-SDK Robolectric environments do not pile up.
        test.maxHeapSize = "2g"
        test.forkEvery = 100
    }
}

/**
 * Robolectric's android-all jars for the SDKs the unit tests run on: 26, 27, 28, 29, 32 and 34 (`@Config(sdk = …)`)
 * and 36 (targetSdk, the default). Coordinates from Robolectric's `DefaultSdkProvider` for the version in the catalog.
 *
 * Robolectric would otherwise download these from Maven Central inside the test JVM, with no retry: one throttled or
 * failed request fails the test class (PR #27 run 45471160023, `AndroidRoleControllerTest` "classMethod",
 * `MavenArtifactFetcher`). Gradle resolves them instead, so they get Gradle's retries (`gradle.properties`), any
 * repository mirror, and setup-gradle's cache. The tests then run offline: `robolectric-deps.properties` maps each jar
 * to its file in Gradle's cache (no copy), and Robolectric fails rather than downloads when an SDK is not listed here.
 * Adding a new `@Config(sdk = …)` level, or updating Robolectric, means adding its entry. docs/ci/RELIABILITY.md.
 */
private val ROBOLECTRIC_ANDROID_ALL =
    mapOf(
        "4.17" to
            listOf(
                "8.0.0_r4-robolectric-r1-i7",
                "8.1.0-robolectric-4611349-i7",
                "9-robolectric-4913185-2-i7",
                "10-robolectric-5803371-i7",
                "12.1-robolectric-8229987-i7",
                "14-robolectric-10818077-i7",
                "16-robolectric-13921718-i7",
            ),
    )

private const val ANDROID_ALL = "org.robolectric:android-all-instrumented"

internal class RobolectricOffline(
    val writeTask: Provider<WriteRobolectricDeps>,
    val args: RobolectricOfflineArgs,
)

private fun Project.robolectricOfflineArgs(): RobolectricOffline {
    val version = libs.findVersion("robolectric").get().requiredVersion
    val jars =
        ROBOLECTRIC_ANDROID_ALL[version]
            ?: error("Robolectric $version: add its android-all jars to ROBOLECTRIC_ANDROID_ALL (AndroidConfig.kt).")
    // One configuration per jar: in a single one, Gradle's conflict resolution would keep only the highest version.
    val configuration =
        files(
            jars.mapIndexed { i, jar ->
                configurations.create("testRobolectricAndroidAll$i") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    isTransitive = false
                    dependencies.add(project.dependencies.create("$ANDROID_ALL:$jar"))
                }
            },
        )
    val write =
        tasks.register("writeRobolectricDeps", WriteRobolectricDeps::class.java) {
            this.jars.from(configuration)
            depsFile.set(layout.buildDirectory.file("robolectric/robolectric-deps.properties"))
        }
    val args =
        objects.newInstance(RobolectricOfflineArgs::class.java).apply {
            this.jars.from(configuration)
            depsFile.set(write.flatMap { it.depsFile })
        }
    return RobolectricOffline(write, args)
}

/** Writes `group:artifact:version=<absolute path>` per android-all jar, for Robolectric's properties resolver. */
abstract class WriteRobolectricDeps : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:OutputFile
    abstract val depsFile: RegularFileProperty

    @TaskAction
    fun write() {
        val prefix = ANDROID_ALL.substringAfter(':') + "-"
        val lines =
            jars.files.sortedBy { it.name }.map { jar ->
                val version = jar.name.removePrefix(prefix).removeSuffix(".jar")
                "${escapeProperty("$ANDROID_ALL:$version")}=${escapeProperty(jar.absolutePath)}"
            }
        depsFile.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

/** Properties format: ':' separates key and value unless escaped, in the key and the path alike. */
private fun escapeProperty(v: String) = v.replace("\\", "/").replace(":", "\\:")

/** Points the test JVM at the jars Gradle resolved; tracked by content, not by their path in Gradle's cache. */
abstract class RobolectricOfflineArgs : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:Internal
    abstract val depsFile: RegularFileProperty

    override fun asArguments(): List<String> =
        listOf("-Drobolectric-deps.properties=${depsFile.get().asFile.absolutePath}", "-Drobolectric.offline=true")
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
