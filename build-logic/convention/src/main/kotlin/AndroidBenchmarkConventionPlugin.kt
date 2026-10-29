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
                namespace = strataNamespace()
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
            registerModuleGraphGate(isAndroid = true)
        }
}
