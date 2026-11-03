plugins {
    alias(libs.plugins.strata.android.library)
    alias(libs.plugins.strata.android.compose)
}

dependencies {
    api(projects.core.model)
    api(projects.core.theme)
    implementation(libs.androidx.activity.compose) // PredictiveBackHandler for sheets
}
