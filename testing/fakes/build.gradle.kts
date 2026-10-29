// Fakes of every :core:platform-api interface (ARCHITECTURE §1, §2.1: no :platform:* here),
// plus in-memory stores for the pure theme library of :core:theme.
plugins { alias(libs.plugins.strata.jvm.library) }

dependencies {
    api(projects.core.platformApi)
    api(projects.core.common)
    api(projects.core.theme)
}
