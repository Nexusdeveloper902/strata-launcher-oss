plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
}
