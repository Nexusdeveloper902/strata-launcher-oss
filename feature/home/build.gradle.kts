plugins {
    alias(libs.plugins.strata.android.library)
    alias(libs.plugins.strata.android.compose)
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.platformApi)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(projects.testing.fakes)
}
