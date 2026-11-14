import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `strata.android.application`: the `:app` module. Kotlin is built into AGP 9. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
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
                lint { configureStrataLint() }
                testOptions {
                    configureStrataUnitTests(target)
                    configureStrataManagedDevices()
                }
            }
            configureKotlinCompile()
            dependencies.add("lintChecks", dependencies.project(mapOf("path" to ":lint-rules")))
            addAndroidTestDependencies()
            addInstrumentedTestDependencies()
            registerPermissionGate()
            registerReleaseApkGate()
            registerModuleGraphGate(isAndroid = true)
            registerDependencyGate("releaseRuntimeClasspath")
        }
}
