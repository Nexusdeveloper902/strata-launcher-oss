// Maven Central's content is read from Google's mirror of it first: Central throttles CI runners with HTTP 429 for
// longer than Gradle's retries last (nightly 45633298267, UTP jars for API 35). Same files (checked by checksum); an
// artifact the mirror lacks answers 404, and Gradle then tries Maven Central itself. docs/ci/RELIABILITY.md.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralMirror" }
        mavenCentral()
    }
}

rootProject.name = "strata-launcher"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Module layout: spec/ARCHITECTURE.md §2. Dependency rules: §2.1 (checked by checkModuleGraph, T-CI-03).
include(":app")

include(":core:common")
include(":core:model")
include(":core:platform-api")
include(":core:json")
include(":core:theme")
include(":core:search")
include(":core:data")
include(":core:ui")

include(":platform:apps")
include(":platform:icons")
include(":platform:widgets")
include(":platform:notifications")
include(":platform:system")

include(":feature:home")
include(":feature:search")
include(":feature:widgets")
include(":feature:settings")
include(":feature:theme")
include(":feature:onboarding")

include(":backup")

include(":testing:fakes")
include(":testing:fixtures")
include(":benchmark")

// Fixture APKs for device tests and benchmarks (authored by us, never shipped).
include(":fixtures:zz")
include(":lint-rules")
