plugins {
    alias(libs.plugins.strata.android.application)
    alias(libs.plugins.strata.android.compose)
}

android {
    defaultConfig {
        applicationId = "app.strata.launcher"
        versionCode = 1
        versionName = "0.0.1-m0"
        buildConfigField("boolean", "STRICT_MODE_DEATH", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // v1 locale set (PRODUCT_SPEC §5). M0 ships `en` only; keep in sync with res/xml/locales_config.xml.
        localeFilters += listOf("en")
    }

    buildTypes {
        debug {
            // StrictMode thread-policy death (docs/impl-notes/M0.md). Off by default: OEM framework code (Samsung
            // One UI) does disk I/O on the main thread and killed the sideloaded debug APK at launch. The emulator
            // smoke tests turn it on with -Pstrata.strictModeDeath=true (tools/ci/run-gmd.sh).
            val strictDeath = providers.gradleProperty("strata.strictModeDeath").orNull == "true"
            buildConfigField("boolean", "STRICT_MODE_DEATH", strictDeath.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // ARCHITECTURE 17: release-like, not debuggable, profileable (src/benchmark manifest), signed with debug
        // keys so Macrobenchmark can install it. Never distributed.
        create("benchmark") {
            initWith(getByName("release"))
            // A separate application, so installing it never replaces (or wipes the data of) the daily build.
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            proguardFiles("benchmark-rules.pro")
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.platformApi)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.core.theme)
    implementation(projects.platform.system)
    implementation(projects.platform.apps)
    implementation(projects.platform.icons)
    implementation(projects.feature.home)
    implementation(projects.feature.search)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.settings)
    implementation(projects.feature.theme)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.testing.fakes)
    testImplementation(projects.testing.fixtures)
    testImplementation(libs.androidx.room.runtime) // T-PRIV-05 opens the real launch-log database
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(projects.testing.fakes)
    androidTestImplementation(libs.androidx.room.runtime)
}
