import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `strata.android.library`: `:core:data`, `:core:ui`, `:platform:*`, `:feature:*`. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = strataNamespace()
            compileSdk = StrataBuild.COMPILE_SDK
            defaultConfig {
                minSdk = StrataBuild.MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = StrataBuild.JAVA_VERSION
                targetCompatibility = StrataBuild.JAVA_VERSION
            }
            lint { configureStrataLint() }
            testOptions { configureStrataUnitTests(target) }
        }
        configureKotlinCompile()
        addAndroidTestDependencies()
    }
}
