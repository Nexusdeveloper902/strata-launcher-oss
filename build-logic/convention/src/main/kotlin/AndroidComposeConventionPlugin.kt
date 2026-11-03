import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** `strata.android.compose`: apply after `strata.android.application` or `strata.android.library`. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.findByType(ApplicationExtension::class.java)?.buildFeatures?.compose = true
            extensions.findByType(LibraryExtension::class.java)?.buildFeatures?.compose = true
            configureComposeCompiler()

            val bom = dependencies.platform(libs.lib("androidx-compose-bom"))
            dependencies.add("implementation", bom)
            dependencies.add("implementation", libs.lib("androidx-compose-ui"))
            dependencies.add("implementation", libs.lib("androidx-compose-foundation"))
            dependencies.add("implementation", libs.lib("androidx-compose-material3"))
            dependencies.add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            dependencies.add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
            dependencies.add("testImplementation", bom)
            dependencies.add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            dependencies.add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
            dependencies.add("androidTestImplementation", bom)
            dependencies.add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            Unit
        }

    /**
     * Stability of model classes from non-Compose modules (`config/compose-stability.conf`, docs/perf/
     * optimization-pass-1.md) and, with `-Pstrata.composeReports=true`, the compiler's stability reports and
     * metrics under `build/compose-reports/` and `build/compose-metrics/` of each module.
     */
    private fun Project.configureComposeCompiler() {
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("config/compose-stability.conf"))
            if (providers.gradleProperty("strata.composeReports").orNull == "true") {
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
            }
        }
    }
}
