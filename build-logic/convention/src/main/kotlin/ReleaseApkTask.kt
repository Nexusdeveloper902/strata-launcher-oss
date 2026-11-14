import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * `checkReleaseApk` (T-CI-07 + the APK halves of T-PRIV-02/03): builds `assembleRelease` and inspects every APK it
 * produced. Rules: [ReleaseApkLogic]. Writes a one-screen report to `build/reports/release-apk.txt`.
 */
abstract class CheckReleaseApkTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val apkDirectory: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val mappingFile: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val approvedFile: RegularFileProperty

        @get:Input
        abstract val ignoredPermissions: SetProperty<String>

        /** `aapt2` from the SDK build-tools AGP uses (absolute path; not a tracked input file). */
        @get:Input
        abstract val aapt2Path: Property<String>

        @get:OutputFile
        abstract val report: RegularFileProperty

        private fun aapt2(vararg args: String): String {
            val out = ByteArrayOutputStream()
            exec.exec {
                commandLine(aapt2Path.get(), *args)
                standardOutput = out
            }
            return out.toString(Charsets.UTF_8)
        }

        @TaskAction
        fun check() {
            val aapt2 = File(aapt2Path.get())
            if (!aapt2.canExecute()) {
                throw GradleException(
                    "checkReleaseApk: aapt2 not found at $aapt2 (install build-tools)",
                )
            }
            val apks =
                apkDirectory
                    .get()
                    .asFile
                    .listFiles { f -> f.extension == "apk" }
                    .orEmpty()
                    .sortedBy { it.name }
            if (apks.isEmpty()) throw GradleException("checkReleaseApk: no APK in ${apkDirectory.get().asFile}")
            val approved = GateLogic.parseList(approvedFile.get().asFile.readText())
            val problems = mutableListOf<String>()
            val lines = mutableListOf<String>()
            for (apk in apks) {
                val entries =
                    ZipFile(apk).use { z ->
                        z.entries().toList().map { ReleaseApkLogic.Entry(it.name, it.compressedSize, it.size) }
                    }
                val sizes = ReleaseApkLogic.perAbiSizes(apk.length(), entries)
                lines +=
                    "${apk.name}: ${apk.length()} bytes; per ABI ${sizes.entries.joinToString {
                        "${it.key}=${it.value}"
                    }}; " +
                    "fonts ${ReleaseApkLogic.fontBytes(entries)} bytes"
                problems += ReleaseApkLogic.sizeViolations(apk.name, apk.length(), entries)
                problems +=
                    ReleaseApkLogic.badgingViolations(
                        aapt2("dump", "badging", apk.path),
                        approved,
                        ignoredPermissions.get(),
                    )
                problems +=
                    ReleaseApkLogic.manifestViolations(
                        aapt2("dump", "xmltree", "--file", "AndroidManifest.xml", apk.path),
                    )
                val dex =
                    ZipFile(apk).use { z ->
                        z.entries().toList().filter { it.name.endsWith(".dex") }.joinToString("\n") {
                            String(z.getInputStream(it).readBytes(), Charsets.ISO_8859_1)
                        }
                    }
                problems += ReleaseApkLogic.dexViolations(dex)
            }
            problems += ReleaseApkLogic.protoRenameViolations(mappingFile.get().asFile.readText())
            val text =
                buildString {
                    lines.forEach { appendLine(it) }
                    appendLine(
                        "budget: ${ReleaseApkLogic.MAX_APK_BYTES} bytes per ABI, fonts ${ReleaseApkLogic.MAX_FONT_BYTES}",
                    )
                    appendLine(if (problems.isEmpty()) "result: pass" else "result: FAIL")
                    problems.forEach { appendLine("  - $it") }
                }
            report
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText(text)
            if (problems.isNotEmpty()) {
                throw GradleException("checkReleaseApk failed with ${problems.size} violation(s):\n$text")
            }
            lines.forEach { logger.lifecycle("checkReleaseApk: $it") }
        }
    }

/** Registers `checkReleaseApk` on the `release` variant of `:app`. */
internal fun Project.registerReleaseApkGate() {
    val android = extensions.getByType<ApplicationExtension>()
    val components = extensions.getByType<ApplicationAndroidComponentsExtension>()
    components.onVariants(components.selector().withBuildType("release")) { variant ->
        tasks.register("checkReleaseApk", CheckReleaseApkTask::class.java) {
            group = "verification"
            description =
                "T-CI-07: release APK size per ABI, fonts, not debuggable/testOnly, no pseudo-locales, no test code."
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            approvedFile.set(rootProject.layout.projectDirectory.file("config/approved-permissions.txt"))
            ignoredPermissions.add(variant.applicationId.map { "$it.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
            aapt2Path.set(
                components.sdkComponents.sdkDirectory.map { sdk ->
                    val dir = sdk.asFile.resolve("build-tools")
                    val wanted = dir.resolve(android.buildToolsVersion).resolve("aapt2")
                    if (wanted.exists()) {
                        wanted.path
                    } else {
                        // Any installed build-tools works for `dump`; prefer the newest.
                        dir
                            .listFiles()
                            .orEmpty()
                            .map { it.resolve("aapt2") }
                            .filter { it.exists() }
                            .maxByOrNull { it.parentFile.name }
                            ?.path ?: wanted.path
                    }
                },
            )
            report.set(layout.buildDirectory.file("reports/release-apk.txt"))
        }
    }
}
