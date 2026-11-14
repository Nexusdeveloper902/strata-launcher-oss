import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `strata.android.benchmark`: the `:benchmark` Macrobenchmark module (`com.android.test`, targets `:app`). */
class AndroidBenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("com.android.test")
            extensions.configure<TestExtension> {
                // Self-instrumenting: this APK's package is its namespace, and must not equal the target's id
                // (`app.strata.launcher.benchmark`, the `:app` benchmark build type). Sources keep their own package.
                namespace = "${StrataBuild.BASE_NAMESPACE}.macrobenchmark"
                compileSdk = StrataBuild.COMPILE_SDK
                defaultConfig {
                    minSdk = StrataBuild.MIN_SDK
                    targetSdk = StrataBuild.TARGET_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = StrataBuild.JAVA_VERSION
                    targetCompatibility = StrataBuild.JAVA_VERSION
                }
                targetProjectPath = ":app"
                // CI correctness run of the Macrobenchmarks (dry run, no budgets): `api34BenchmarkAndroidTest`.
                // Same device definition as the :app smoke device, so both share the cached system image.
                testOptions.managedDevices.localDevices.create("api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "google"
                }
                buildTypes {
                    // Release-like build of :app (see its `benchmark` build type); this one signs with debug keys.
                    create("benchmark") {
                        isDebuggable = true
                        signingConfig = signingConfigs.getByName("debug")
                        matchingFallbacks += listOf("release")
                    }
                }
                experimentalProperties["android.experimental.self-instrumenting"] = true
            }
            // Only the `benchmark` variant makes sense for a Macrobenchmark module.
            extensions.configure<com.android.build.api.variant.TestAndroidComponentsExtension> {
                beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
            }
            configureKotlinCompile()
            dependencies.add("implementation", libs.lib("androidx-test-ext-junit"))
            dependencies.add("implementation", libs.lib("androidx-test-uiautomator"))
            dependencies.add("implementation", libs.lib("androidx-benchmark-macro-junit4"))
            dependencies.add("implementation", libs.lib("androidx-benchmark-traceprocessor"))
            registerModuleGraphGate(isAndroid = true)
        }
}
