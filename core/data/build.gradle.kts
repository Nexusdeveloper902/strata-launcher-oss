plugins {
    alias(libs.plugins.strata.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    sourceSets {
        // Exported Room schemas (ARCHITECTURE §4.1, T-LIFE-14) are test inputs for MigrationTestHelper.
        getByName("test").assets.directories.add("$projectDir/schemas")
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// settings.proto (ARCHITECTURE §4.2): protobuf-lite Java classes for Proto DataStore.
protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api(projects.core.platformApi)
    api(projects.core.theme) // ThemeRepository and the store interfaces are in its public API
    implementation(libs.androidx.core.ktx) // AtomicFile (THEME_SYSTEM §11.3)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.protobuf.javalite)
    ksp(libs.androidx.room.compiler)

    testImplementation(projects.testing.fakes)
    testImplementation(projects.testing.fixtures)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.room.testing)
    // T-THEME-45 device test (AtomicPersistenceDeviceTest): run with :core:data:connectedDebugAndroidTest.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    // T-THEME-28 / T-THEME-29 / T-THEME-51 device tests (ThemeImportDeviceTest).
    androidTestImplementation(projects.testing.fixtures)
    androidTestImplementation(projects.testing.fakes)
}

// Built-in presets (THEME_SYSTEM §14): spec/sample-themes/ is the single source, copied into the generated
// assets as `assets/themes/` and merged into the APK. Nothing is committed twice. Task type: build-logic.
val syncThemeAssets by tasks.registering(SyncThemeAssets::class) {
    themes.from(
        rootProject.layout.projectDirectory.dir("spec/sample-themes").asFileTree.matching {
            include("*.strata-theme.json")
        },
    )
    output.set(layout.buildDirectory.dir("generated/themeAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncThemeAssets, SyncThemeAssets::output)
    }
}
