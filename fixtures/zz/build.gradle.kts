// The "Zz Test" fixture app (TEST_PLAN T-LIST-08, T-APPS-02, T-APPS-03, T-PERF-10; docs/impl-notes/M1.md). Authored
// by us, plain Java, never shipped. Build and push it for the device tests and the benchmark:
//   ./gradlew :fixtures:zz:assembleDebug
//   adb push fixtures/zz/build/outputs/apk/debug/zz-debug.apk /data/local/tmp/strata-fixture-zz.apk
// Debuggable on purpose: T-APPS-02 reads its recorded source bounds with `run-as`.
plugins { alias(libs.plugins.android.application) }

android {
    namespace = "app.strata.fixture.zz"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.strata.fixture.zz"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
