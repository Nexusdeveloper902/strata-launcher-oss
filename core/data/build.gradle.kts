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
}
