import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Fixed build constants: spec/ARCHITECTURE.md §17, NFR-COMPAT-01. */
object StrataBuild {
    const val BASE_NAMESPACE = "app.strata.launcher"
    const val COMPILE_SDK = 36
    const val TARGET_SDK = 36
    const val MIN_SDK = 26
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    val JVM_TARGET: JvmTarget = JvmTarget.JVM_17
}
