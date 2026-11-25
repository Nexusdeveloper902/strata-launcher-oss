import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Checks of the built-in preset assets (THEME_SYSTEM §14, §14.2, T-THEME-60): a preset may ship a wallpaper set, the
 * list `<preset>.wallpapers.txt` (`<sha256> <label>` per line, `#` comments) next to its `<preset>.strata-theme.json`,
 * and the images `<sha256>.webp`. Every problem is reported; the build fails on any.
 */
object ThemeAssetLogic {
    const val THEME_SUFFIX = ".strata-theme.json"
    const val LIST_SUFFIX = ".wallpapers.txt"
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    const val MAX_LABEL = 64
    private val LINE = Regex("^([0-9a-f]{64}) (\\S.{0,${MAX_LABEL - 1}})$")
    private val IMAGE_NAME = Regex("^([0-9a-f]{64})\\.webp$")
    private val THEME_IMAGE = Regex("\"image\"\\s*:\\s*\\{[^}]*\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")

    data class Wallpaper(val sha256: String, val label: String)

    /** Strict parse of a wallpaper list. */
    fun parseList(text: String): Pair<List<Wallpaper>, List<String>> {
        val out = mutableListOf<Wallpaper>()
        val problems = mutableListOf<String>()
        val seen = hashSetOf<String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
            val m = LINE.matchEntire(line)
            when {
                m == null -> problems += "line ${i + 1}: expected '<sha256> <label>' (label 1-$MAX_LABEL characters)"
                !seen.add(m.groupValues[1]) -> problems += "line ${i + 1}: duplicate image ${m.groupValues[1]}"
                else -> out += Wallpaper(m.groupValues[1], m.groupValues[2].trim())
            }
        }
        if (out.isEmpty() && problems.isEmpty()) problems += "no wallpapers listed"
        return out to problems
    }

    /** The `wallpaper.image.sha256` of a preset file, lower case, or null. */
    fun themeImage(json: String): String? =
        THEME_IMAGE
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** RIFF....WEBP */
    fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"

    /**
     * All problems of a preset set: [themes] are the preset files and lists by name, [images] the image files. Each list
     * needs its preset; each listed image must exist, be a WebP within the size cap, and hash to its name; a preset's
     * own `wallpaper.image`, when shipped, must be the first line of its list; and no image may be left unlisted.
     */
    fun problems(
        themes: Map<String, String>,
        images: Map<String, ByteArray>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val listed = hashSetOf<String>()
        for ((name, text) in themes.filterKeys { it.endsWith(LIST_SUFFIX) }.toSortedMap()) {
            val preset = name.removeSuffix(LIST_SUFFIX) + THEME_SUFFIX
            val (entries, lineProblems) = parseList(text)
            problems += lineProblems.map { "$name: $it" }
            val json = themes[preset]
            if (json == null) {
                problems += "$name: no preset $preset"
                continue
            }
            val own = themeImage(json)
            if (own != null && entries.firstOrNull()?.sha256 != own) {
                problems += "$name: the first image must be $preset's wallpaper.image ($own)"
            }
            for (e in entries) {
                listed += e.sha256
                val bytes = images["${e.sha256}.webp"]
                when {
                    bytes == null -> problems += "$name: missing image ${e.sha256}.webp"
                    bytes.size > MAX_IMAGE_BYTES -> problems += "${e.sha256}.webp: larger than $MAX_IMAGE_BYTES bytes"
                    !isWebp(bytes) -> problems += "${e.sha256}.webp: not a WebP file"
                    sha256(bytes) != e.sha256 -> problems += "${e.sha256}.webp: content hash does not match its name"
                }
            }
        }
        for ((name, json) in themes.filterKeys { it.endsWith(THEME_SUFFIX) }.toSortedMap()) {
            val own = themeImage(json) ?: continue
            if (own !in listed && "$own.webp" in images) {
                problems += "$name: wallpaper.image $own is shipped but not in a wallpaper list"
            }
        }
        for (name in images.keys.sorted()) {
            val m = IMAGE_NAME.matchEntire(name)
            if (m == null) {
                problems += "$name: image names are <sha256>.webp"
            } else if (m.groupValues[1] !in listed) {
                problems += "$name: not in any wallpaper list"
            }
        }
        return problems
    }
}

/**
 * Copies the built-in preset files (THEME_SYSTEM §14, single source `spec/sample-themes/`, plus a private overlay when
 * present) into `<output>/themes/`, a generated assets directory, so the APK carries them as `assets/themes/`. Preset
 * wallpaper lists go next to them and the images into `themes/images/` (§14.2), after [ThemeAssetLogic]'s checks.
 */
abstract class SyncThemeAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val themes: ConfigurableFileCollection

    /** Preset wallpaper images (`<sha256>.webp`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val images: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun sync() {
        val root = output.get().asFile
        root.deleteRecursively()
        val dir = root.resolve("themes").apply { mkdirs() }
        val suffixes = listOf(ThemeAssetLogic.THEME_SUFFIX, ThemeAssetLogic.LIST_SUFFIX)
        val files = themes.files.filter { f -> f.isFile && suffixes.any { f.name.endsWith(it) } }
        check(files.any { it.name.endsWith(ThemeAssetLogic.THEME_SUFFIX) }) { "no built-in themes found" }
        val duplicates = files.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw GradleException("built-in presets: duplicate file names $duplicates")
        val imageFiles = images.files.filter { it.isFile }
        val problems =
            ThemeAssetLogic.problems(
                files.associate { it.name to it.readText() },
                imageFiles.associate { it.name to it.readBytes() },
            )
        if (problems.isNotEmpty()) {
            throw GradleException("built-in preset assets:\n" + problems.joinToString("\n") { "  - $it" })
        }
        files.forEach { it.copyTo(dir.resolve(it.name)) }
        if (imageFiles.isNotEmpty()) {
            val out = dir.resolve("images").apply { mkdirs() }
            imageFiles.forEach { it.copyTo(File(out, it.name)) }
        }
    }
}
