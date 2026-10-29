plugins {
    alias(libs.plugins.strata.android.library)
    alias(libs.plugins.strata.android.compose)
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.platformApi)
}
