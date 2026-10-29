// Empty until its milestone (IMPLEMENTATION_ROADMAP §1); the compose plugin is added then.
plugins { alias(libs.plugins.strata.android.library) }

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.platformApi)
}
