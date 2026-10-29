import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No catalog entry '$alias'") }

/** `:core:platform-api` -> `app.strata.launcher.core.platformapi`. `:app` keeps the base name. */
internal fun Project.strataNamespace(): String {
    val base = StrataBuild.BASE_NAMESPACE
    if (path == ":app") return base
    return base + path.replace(':', '.').replace("-", "")
}
