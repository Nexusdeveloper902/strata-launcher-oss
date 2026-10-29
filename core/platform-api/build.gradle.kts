plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    api(projects.core.model)
    api(projects.core.common)
}
