plugins { alias(libs.plugins.strata.android.library) }

dependencies {
    api(projects.core.platformApi)
    implementation(libs.androidx.core.ktx)
}
