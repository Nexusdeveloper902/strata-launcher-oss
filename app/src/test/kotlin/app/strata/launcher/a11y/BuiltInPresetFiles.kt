package app.strata.launcher.a11y

import java.io.File

/**
 * The built-in preset files the APK packages (THEME_SYSTEM §14): `spec/sample-themes/` plus, in the private repository,
 * the presets of the private overlay `themes-private/presets/` (CONTRIBUTING "Public mirror and CI"; absent on the
 * public mirror). Paths are relative to the module directory Gradle runs the tests in.
 */
object BuiltInPresetFiles {
    private const val SUFFIX = ".strata-theme.json"

    private fun dirs(): List<File> {
        val samples = listOf(File("../spec/sample-themes"), File("spec/sample-themes")).first { it.isDirectory }
        val overlay =
            listOf(
                File("../themes-private/presets"),
                File("themes-private/presets"),
            ).firstOrNull { it.isDirectory }
        return listOfNotNull(samples, overlay)
    }

    /** Preset names (file names without the suffix), sorted. */
    fun names(): List<String> =
        dirs()
            .flatMap { d -> d.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().toList() }
            .map { it.name.removeSuffix(SUFFIX) }
            .sorted()

    fun bytes(name: String): ByteArray = dirs().map { File(it, name + SUFFIX) }.first { it.isFile }.readBytes()
}
