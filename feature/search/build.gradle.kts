plugins {
    alias(libs.plugins.strata.android.library)
    alias(libs.plugins.strata.android.compose)
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.platformApi)
    implementation(projects.core.search)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(projects.core.data) // the real list pipeline with profile state (T-SEARCH-08)
    testImplementation(projects.testing.fakes)
    testImplementation(projects.testing.fixtures)
}
