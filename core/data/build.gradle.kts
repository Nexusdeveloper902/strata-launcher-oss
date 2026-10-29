plugins { alias(libs.plugins.strata.android.library) }

dependencies {
    api(projects.core.platformApi)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.coroutines.android)
}
