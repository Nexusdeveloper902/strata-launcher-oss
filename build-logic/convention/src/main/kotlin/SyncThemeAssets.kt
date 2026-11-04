import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies the built-in preset files (THEME_SYSTEM §14, single source `spec/sample-themes/`) into
 * `<output>/themes/`, a generated assets directory, so the APK carries them as `assets/themes/`.
 */
abstract class SyncThemeAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val themes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun sync() {
        val root = output.get().asFile
        root.deleteRecursively()
        val dir = root.resolve("themes").apply { mkdirs() }
        val files = themes.files.filter { it.isFile && it.name.endsWith(".strata-theme.json") }
        check(files.isNotEmpty()) { "no built-in themes found" }
        files.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}
