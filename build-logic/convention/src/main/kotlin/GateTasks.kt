import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

private fun fail(gate: String, problems: List<String>): Nothing =
    throw GradleException(
        buildString {
            appendLine("$gate failed with ${problems.size} violation(s):")
            problems.forEach { appendLine("  - $it") }
        },
    )

/** T-CI-01, T-PRIV-03: merged release manifest permissions must equal `config/approved-permissions.txt`. */
abstract class CheckPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val approvedFile: RegularFileProperty

    @get:Input
    abstract val ignoredPermissions: SetProperty<String>

    @TaskAction
    fun check() {
        val found = GateLogic.readManifestPermissions(mergedManifest.get().asFile)
        val approved = GateLogic.parseList(approvedFile.get().asFile.readText())
        val problems = GateLogic.permissionViolations(found, approved, ignoredPermissions.get())
        if (problems.isNotEmpty()) fail("checkPermissions", problems)
        logger.lifecycle("checkPermissions: ${found.used - ignoredPermissions.get()} matches the approved set")
    }
}

/** T-CI-02, T-PRIV-02: every resolved runtime artifact must be on the allow-list and not forbidden. */
abstract class CheckDependenciesTask : DefaultTask() {
    @get:Input
    abstract val resolvedModules: SetProperty<String>

    @get:Input
    abstract val configurationName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowListFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val forbiddenFile: RegularFileProperty

    @TaskAction
    fun check() {
        val problems =
            GateLogic.dependencyViolations(
                resolved = resolvedModules.get(),
                allowed = GateLogic.parseList(allowListFile.get().asFile.readText()),
                forbidden = GateLogic.parseList(forbiddenFile.get().asFile.readText()),
                configurationName = configurationName.get(),
            )
        if (problems.isNotEmpty()) fail("checkDependencies", problems)
    }
}

/** T-CI-03: ARCHITECTURE §2.1 dependency rules for one module. */
abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val androidModule: Property<Boolean>

    @get:Input
    abstract val projectDependencies: SetProperty<String>

    @get:Input
    abstract val externalDependencies: SetProperty<String>

    @TaskAction
    fun check() {
        val problems =
            GateLogic.moduleGraphViolations(
                modulePath.get(),
                androidModule.get(),
                projectDependencies.get(),
                externalDependencies.get(),
            )
        if (problems.isNotEmpty()) fail("checkModuleGraph", problems)
    }
}

/** T-CI-08: no entitlement symbols in `src/main` (and theme schema/samples). */
abstract class CheckEntitlementsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val rootPath: Property<String>

    @TaskAction
    fun check() {
        val root = java.io.File(rootPath.get())
        val problems =
            sources.files.sorted().flatMap { file ->
                val label = file.relativeToOrSelf(root).path
                GateLogic.entitlementViolations(label, file.readText())
            }
        if (problems.isNotEmpty()) fail("checkEntitlements", problems)
    }
}
