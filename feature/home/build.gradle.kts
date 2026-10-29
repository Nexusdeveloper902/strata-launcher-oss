plugins {
    alias(libs.plugins.strata.android.library)
    alias(libs.plugins.strata.android.compose)
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.platformApi)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(projects.core.data) // AppListAssembler builds realistic lists for the Compose tests
    testImplementation(projects.testing.fakes)
    testImplementation(projects.testing.fixtures)
}
