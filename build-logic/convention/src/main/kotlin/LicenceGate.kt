import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * T-CI-05 (FR-SET-06): the About -> Licences data stays complete.
 * - every bundled font file (`res/font/`, `assets/fonts/`) has a `fonts` entry in `licences.json` naming it, whose
 *   licence is OFL-1.1 and whose licence file exists and contains the SIL Open Font License text;
 * - every runtime allow-list line (`config/dependency-allowlist.txt`, T-CI-02) is covered by a `libraries` entry;
 * - every `licenceFile` named in `licences.json` exists.
 */
object LicenceLogic {
    @Suppress("UNCHECKED_CAST")
    fun violations(
        licencesJson: String,
        fontFiles: List<String>,
        allowList: Set<String>,
        licenceFileText: (String) -> String?,
    ): List<String> {
        val problems = mutableListOf<String>()
        val root =
            try {
                JsonSlurper().parseText(licencesJson) as Map<String, Any?>
            } catch (e: Exception) {
                return listOf("licences.json is not valid JSON: ${e.message}")
            }
        val fonts = (root["fonts"] as? List<Map<String, Any?>>).orEmpty()
        val libraries = (root["libraries"] as? List<Map<String, Any?>>).orEmpty()
        for (entry in fonts + libraries) {
            val file = entry["licenceFile"] as? String
            if (file == null) {
                problems += "entry '${entry["name"]}' has no licenceFile"
            } else if (licenceFileText(file) == null) {
                problems += "entry '${entry["name"]}': licence file $file is missing"
            }
        }
        for (font in fontFiles) {
            val entry = fonts.firstOrNull { (it["files"] as? List<*>)?.contains(font) == true }
            when {
                entry == null -> {
                    problems += "font $font has no entry in licences.json"
                }

                entry["licence"] != "OFL-1.1" -> {
                    problems += "font $font: licence must be OFL-1.1"
                }

                licenceFileText(
                    entry["licenceFile"] as? String ?: "",
                )?.contains("SIL OPEN FONT LICENSE", ignoreCase = true) !=
                    true -> {
                    problems += "font $font: licence file does not contain the SIL Open Font License"
                }
            }
        }
        val covered = libraries.flatMap { (it["covers"] as? List<*>).orEmpty().filterIsInstance<String>() }.toSet()
        for (line in allowList) {
            if (line !in covered) problems += "allow-listed artifact '$line' has no licence entry"
        }
        return problems
    }
}

abstract class CheckLicencesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licencesJson: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetsDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowList: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fonts: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val assets = assetsDir.get().asFile
        val problems =
            LicenceLogic.violations(
                licencesJson = licencesJson.get().asFile.readText(),
                fontFiles =
                    fonts.files
                        .filter { it.isFile }
                        .map { it.name }
                        .sorted(),
                allowList = GateLogic.parseList(allowList.get().asFile.readText()),
            ) { path ->
                File(
                    assets,
                    path,
                ).takeIf { it.isFile && it.canonicalPath.startsWith(assets.canonicalPath) }?.readText()
            }
        if (problems.isNotEmpty()) {
            throw GradleException("checkLicences (T-CI-05) failed:\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}
