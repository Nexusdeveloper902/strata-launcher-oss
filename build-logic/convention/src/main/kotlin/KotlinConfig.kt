import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureKotlinCompile() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(StrataBuild.JVM_TARGET)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
}

internal fun Project.addCommonTestDependencies() {
    dependencies.add("testImplementation", libs.lib("junit4"))
    dependencies.add("testImplementation", libs.lib("kotlin-test"))
    dependencies.add("testImplementation", libs.lib("truth"))
    dependencies.add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    dependencies.add("testImplementation", libs.lib("turbine"))
}
