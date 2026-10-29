plugins {
    `kotlin-dsl`
}

group = "app.strata.launcher.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "strata.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "strata.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "strata.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("rootGates") {
            id = "strata.gates"
            implementationClass = "RootGatesConventionPlugin"
        }
        register("jvmLibrary") {
            id = "strata.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
