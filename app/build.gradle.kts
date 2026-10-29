plugins {
    alias(libs.plugins.strata.android.application)
    alias(libs.plugins.strata.android.compose)
}

android {
    defaultConfig {
        applicationId = "app.strata.launcher"
        versionCode = 1
        versionName = "0.0.1-m0"
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // v1 locale set (PRODUCT_SPEC §5). M0 ships `en` only; keep in sync with res/xml/locales_config.xml.
        localeFilters += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // ARCHITECTURE 17: release-like, not debuggable, profileable (src/benchmark manifest), signed with debug
        // keys so Macrobenchmark can install it. Never distributed.
        create("benchmark") {
            initWith(getByName("release"))
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
    implementation(projects.platform.system)
    implementation(projects.feature.home)
    implementation(projects.feature.onboarding)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(projects.testing.fakes)
}
