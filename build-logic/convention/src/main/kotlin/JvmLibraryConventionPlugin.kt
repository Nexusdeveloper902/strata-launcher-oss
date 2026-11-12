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
            // Pure modules run on Android down to API 26, whose java.* APIs are roughly Java 8 (plus desugaring). Their
            // main code is compiled against the Java 8 API, so a newer JDK call (e.g. LocalDate.ofInstant, API 31) fails
            // the build instead of a device at runtime (M8 part 2, CI run on API 26). Tests keep the full JDK.
            // `:lint-rules` runs inside lint on the build JDK (its lint-api dependencies need Java 17), so it is exempt.
            if (path != ":lint-rules") configureJava8Api()
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

private fun Project.configureJava8Api() {
    tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configure {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjdk-release=1.8")
        }
    }
    tasks.named("compileJava", org.gradle.api.tasks.compile.JavaCompile::class.java).configure {
        options.release.set(8)
    }
}
