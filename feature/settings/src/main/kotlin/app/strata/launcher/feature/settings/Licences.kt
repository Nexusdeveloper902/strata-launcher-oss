package app.strata.launcher.feature.settings

import org.json.JSONException
import org.json.JSONObject

/** One row of About -> Licences (FR-SET-06): a bundled font or an open-source library. */
data class LicenceEntry(
    val name: String,
    /** SPDX id, e.g. `Apache-2.0`, `OFL-1.1`. */
    val licence: String,
    /** Asset path of the full licence text. */
    val licenceFile: String,
    val font: Boolean,
)

/** Loaded licence list; [failed] when `licences.json` could not be read. */
data class LicencesUi(
    val entries: List<LicenceEntry> = emptyList(),
    val failed: Boolean = false,
)

/** `assets/licences.json` (checked at build time by `checkLicences`, T-CI-05). */
object Licences {
    const val ASSET = "licences.json"

    /** Fonts first, then libraries, each in file order. Throws [JSONException] on a malformed file. */
    fun parse(json: String): List<LicenceEntry> {
        val root = JSONObject(json)

        fun list(
            key: String,
            font: Boolean,
        ): List<LicenceEntry> {
            val array = root.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                LicenceEntry(o.getString("name"), o.getString("licence"), o.getString("licenceFile"), font)
            }
        }
        return list("fonts", font = true) + list("libraries", font = false)
    }

    /** Licence texts are read only from `licences/` (never an arbitrary asset path). */
    fun isLicencePath(path: String): Boolean =
        path.startsWith("licences/") && !path.contains("..") && path.endsWith(".txt")
}
