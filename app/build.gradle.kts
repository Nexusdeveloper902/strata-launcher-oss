import java.util.Properties

plugins {
    alias(libs.plugins.strata.android.application)
    alias(libs.plugins.strata.android.compose)
}

/**
 * Release signing (docs/release/RELEASE_GATE.md). Never committed: the values come from environment variables
 * (`STRATA_RELEASE_STORE_FILE`, `STRATA_RELEASE_STORE_PASSWORD`, `STRATA_RELEASE_KEY_ALIAS`,
 * `STRATA_RELEASE_KEY_PASSWORD`) or from the same keys in lower case with dots in `local.properties`
 * (`strata.release.storeFile`, …). Without them the release APK is unsigned, unless `-Pstrata.release.debugSigned=true`
 * (CI) signs it with the debug key so it can be installed and measured like a signed APK.
 */
val localProperties =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
            .asText.orNull
            ?.let { load(it.reader()) }
    }

fun releaseSigning(key: String): String? =
    providers.environmentVariable("STRATA_RELEASE_" + key.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()).orNull
        ?: localProperties.getProperty("strata.release.$key")

android {
    defaultConfig {
        applicationId = "app.strata.launcher"
        // v1 release candidate (docs/release/RELEASE_NOTES_v1.md). The owner sets "1.0.0" when the gate passes.
        versionCode = 2
        versionName = "1.0.0-rc.1"
        buildConfigField("boolean", "STRICT_MODE_DEATH", "false")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // v1 locale set (PRODUCT_SPEC §5): the translated locales so far; keep in sync with res/xml/locales_config.xml.
        // en-XA / ar-XB are the debug pseudo-locales (T-I18N-01); release builds generate no resources for them.
        localeFilters += listOf("en", "de", "en-rXA", "ar-rXB")
    }

    val releaseStore = releaseSigning("storeFile")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = releaseSigning("storePassword")
            keyAlias = releaseSigning("keyAlias")
            keyPassword = releaseSigning("keyPassword")
        }
    }
    val debugSignedRelease = providers.gradleProperty("strata.release.debugSigned").orNull == "true"

    buildTypes {
        debug {
            // T-I18N-01: en-XA (accented, expanded) and ar-XB (mirrored) in the system language list.
            isPseudoLocalesEnabled = true
            // StrictMode thread-policy death (docs/impl-notes/M0.md). Off by default: OEM framework code (Samsung
            // One UI) does disk I/O on the main thread and killed the sideloaded debug APK at launch. The emulator
            // smoke tests turn it on with -Pstrata.strictModeDeath=true (tools/ci/run-gmd.sh).
            val strictDeath = providers.gradleProperty("strata.strictModeDeath").orNull == "true"
            buildConfigField("boolean", "STRICT_MODE_DEATH", strictDeath.toString())
        }
        release {
            // T-CI-07 / NFR-PERF-07: R8 full mode (AGP default) with optimisation, resource shrinking; rules and the
            // reasons for them in proguard-rules.pro. `checkReleaseApk` verifies the result.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isPseudoLocalesEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                when {
                    releaseStore != null -> signingConfigs.getByName("release")
                    debugSignedRelease -> signingConfigs.getByName("debug")
                    else -> null
                }
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
    implementation(projects.platform.notifications)
    implementation(projects.platform.widgets)
    implementation(projects.feature.home)
    implementation(projects.feature.search)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.settings)
    implementation(projects.feature.theme)
    implementation(projects.feature.widgets)
    implementation(projects.backup)

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
    androidTestImplementation(projects.testing.fixtures)
    androidTestImplementation(libs.androidx.room.runtime)
}
