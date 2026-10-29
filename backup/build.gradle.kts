plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.json)
}
