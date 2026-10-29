import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/** `strata.jvm.library`: pure Kotlin modules (no Android SDK), ARCHITECTURE §2.1. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = StrataBuild.JAVA_VERSION
                targetCompatibility = StrataBuild.JAVA_VERSION
            }
            configureKotlinCompile()
            addCommonTestDependencies()
            // Lets `./gradlew testDebugUnitTest` run pure-JVM tests together with the Android modules' tests.
            tasks.register("testDebugUnitTest") {
                group = "verification"
                description = "Alias for `test` in pure Kotlin modules."
                dependsOn("test")
            }
            registerModuleGraphGate(isAndroid = false)
            registerDependencyGate("runtimeClasspath")
        }
}
