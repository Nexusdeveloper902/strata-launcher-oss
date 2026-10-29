import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.kotlin.dsl.getByType

private const val GATES_GROUP = "verification"

/** `checkDependencies` for a module: `releaseRuntimeClasspath` (Android) or `runtimeClasspath` (JVM). */
internal fun Project.registerDependencyGate(configurationName: String) =
    afterEvaluate {
        val config = configurations.named(configurationName)
        tasks.register("checkDependencies", CheckDependenciesTask::class.java) {
            group = GATES_GROUP
            description = "T-CI-02: $configurationName must only contain allow-listed artifacts (ARCHITECTURE 15)."
            this.configurationName.set(configurationName)
            resolvedModules.set(
                config.flatMap { it.incoming.resolutionResult.rootComponent }.map { collectModuleIds(it) },
            )
            allowListFile.set(rootProject.layout.projectDirectory.file("config/dependency-allowlist.txt"))
            forbiddenFile.set(rootProject.layout.projectDirectory.file("config/forbidden-dependencies.txt"))
        }
    }

private fun collectModuleIds(root: ResolvedComponentResult): Set<String> {
    val seen = hashSetOf<ResolvedComponentResult>()
    val ids = sortedSetOf<String>()

    fun walk(c: ResolvedComponentResult) {
        if (!seen.add(c)) return
        (c.id as? ModuleComponentIdentifier)?.let { ids += "${it.group}:${it.module}" }
        c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
    }
    walk(root)
    return ids
}

/** `checkModuleGraph` for a module: declared (non-test) project and external dependencies. */
internal fun Project.registerModuleGraphGate(isAndroid: Boolean) {
    val projectPath = path

    fun declared() =
        configurations
            .filter {
                val n = it.name.lowercase()
                !n.contains("test") && (
                    n.endsWith("api") || n.endsWith("implementation") ||
                        n.endsWith("compileonly") || n.endsWith("runtimeonly")
                )
            }.flatMap { it.dependencies }
    tasks.register("checkModuleGraph", CheckModuleGraphTask::class.java) {
        group = GATES_GROUP
        description = "T-CI-03: ARCHITECTURE 2.1 dependency rules."
        modulePath.set(projectPath)
        androidModule.set(isAndroid)
        projectDependencies.set(provider { declared().filterIsInstance<ProjectDependency>().map { it.path }.toSet() })
        externalDependencies.set(
            provider {
                declared().filterIsInstance<ExternalModuleDependency>().map { "${it.group}:${it.name}" }.toSet()
            },
        )
    }
}

/** `checkPermissions` on the merged release manifest of `:app`. */
internal fun Project.registerPermissionGate() {
    extensions.getByType<ApplicationAndroidComponentsExtension>().apply {
        onVariants(selector().withBuildType("release")) { variant ->
            tasks.register("checkPermissions", CheckPermissionsTask::class.java) {
                group = GATES_GROUP
                description =
                    "T-CI-01 / T-PRIV-03: merged release manifest permissions equal config/approved-permissions.txt."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                approvedFile.set(rootProject.layout.projectDirectory.file("config/approved-permissions.txt"))
                // androidx.core's own signature permission for ContextCompat.registerReceiver (API < 33).
                ignoredPermissions.add(variant.applicationId.map { "$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
            }
        }
    }
}
