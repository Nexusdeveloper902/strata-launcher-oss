// Fakes of every :core:platform-api interface (ARCHITECTURE §1, §2.1: no :platform:* here).
plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    api(projects.core.platformApi)
    api(projects.core.common)
}
