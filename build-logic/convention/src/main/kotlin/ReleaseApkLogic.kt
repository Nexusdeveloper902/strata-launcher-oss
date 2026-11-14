/**
 * Pure logic behind `checkReleaseApk` (T-CI-07, plus the APK halves of T-PRIV-02 and T-PRIV-03). Free of Gradle types
 * so `build-logic/convention/src/test` proves every rule fails on a violation.
 *
 * Inputs are what the task reads from the built release APK: its zip entries, `aapt2 dump badging`, `aapt2 dump
 * xmltree` of the manifest, the dex string pool and the R8 mapping file.
 */
internal object ReleaseApkLogic {
    /** NFR-PERF-07: ≤ 12 MB per ABI. Decimal megabytes, the stricter reading. */
    const val MAX_APK_BYTES: Long = 12_000_000

    /** T-CI-07: fonts ≤ 3 MB in total (uncompressed). */
    const val MAX_FONT_BYTES: Long = 3_000_000

    class Entry(val name: String, val compressedSize: Long, val size: Long)

    /**
     * The size each per-ABI split would have: the APK minus the native libraries of every other ABI. With no
     * native code the APK is ABI-independent and reported once as `universal`. Zip local headers of the removed
     * entries are not subtracted, so the estimate is slightly high (on the safe side).
     */
    fun perAbiSizes(apkBytes: Long, entries: List<Entry>): Map<String, Long> {
        val byAbi = entries.filter { it.name.startsWith("lib/") }.groupBy { it.name.split('/').getOrElse(1) { "" } }
        if (byAbi.isEmpty()) return mapOf("universal" to apkBytes)
        return byAbi.keys.sorted().associateWith { abi ->
            apkBytes -
                byAbi
                    .filterKeys { it != abi }
                    .values
                    .flatten()
                    .sumOf { it.compressedSize }
        }
    }

    private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".ttc", ".woff", ".woff2")

    /** Fonts by extension: resource shrinking renames `res/font/x.ttf` to short names but keeps the extension. */
    fun fontBytes(entries: List<Entry>): Long =
        entries.filter { e -> FONT_EXTENSIONS.any { e.name.lowercase().endsWith(it) } }.sumOf { it.size }

    fun sizeViolations(apkName: String, apkBytes: Long, entries: List<Entry>): List<String> {
        val out = mutableListOf<String>()
        perAbiSizes(apkBytes, entries).forEach { (abi, bytes) ->
            if (bytes > MAX_APK_BYTES) out += "$apkName ($abi): $bytes bytes > $MAX_APK_BYTES (NFR-PERF-07, T-CI-07)"
        }
        val fonts = fontBytes(entries)
        if (fonts > MAX_FONT_BYTES) out += "$apkName: fonts $fonts bytes > $MAX_FONT_BYTES (T-CI-07)"
        return out
    }

    /** Pseudo-locales are debug-only (T-I18N-01): `en-XA`, `ar-XB` must not reach a release APK. */
    private val PSEUDO_LOCALE = Regex("""'[a-z]{2,3}-X[AB]'""")

    /**
     * `aapt2 dump badging`: not debuggable, not test-only, no pseudo-locales, and the permission set equals the
     * approved one (T-PRIV-03 on the APK itself, not only on the merged manifest).
     */
    fun badgingViolations(badging: String, approved: Set<String>, ignored: Set<String>): List<String> {
        val out = mutableListOf<String>()
        val lines = badging.lines()
        if (lines.any { it.trim() == "application-debuggable" }) out += "release APK is debuggable"
        if (lines.any { it.startsWith("testOnly=") }) out += "release APK is marked testOnly"
        lines.firstOrNull { it.startsWith("locales:") }?.let { l ->
            val pseudo = PSEUDO_LOCALE.findAll(l).map { it.value.trim('\'') }.toList()
            if (pseudo.isNotEmpty()) {
                out +=
                    "release APK carries pseudo-locale resources: $pseudo (debug only, T-I18N-01)"
            }
        }
        val used =
            lines
                .filter { it.startsWith("uses-permission:") || it.startsWith("uses-permission-sdk-23:") }
                .mapNotNull { Regex("""name='([^']+)'""").find(it)?.groupValues?.get(1) }
                .toSet() - ignored
        val forbidden = used.intersect(GateLogic.FORBIDDEN_PERMISSIONS)
        if (forbidden.isNotEmpty()) out += "release APK requests forbidden permission(s): ${forbidden.sorted()}"
        val extra = used - approved - forbidden
        if (extra.isNotEmpty()) {
            out +=
                "release APK permission(s) not in config/approved-permissions.txt: ${extra.sorted()}"
        }
        val missing = approved - used
        if (missing.isNotEmpty()) out += "approved permission(s) missing from the release APK: ${missing.sorted()}"
        return out
    }

    /** Component or attribute values that only test builds may carry. */
    private val TEST_ONLY_NAME = Regex("""(?i)fixture|androidx\.test\.|org\.junit|robolectric|leakcanary|\.test\.""")

    /**
     * `aapt2 dump xmltree --file AndroidManifest.xml`: no `debuggable`/`testOnly` attribute set to true, and no
     * component whose class looks like test code (fixtures, the AndroidX test runner, LeakCanary).
     */
    fun manifestViolations(xmltree: String): List<String> {
        val out = mutableListOf<String>()
        var element = ""
        for (raw in xmltree.lines()) {
            val line = raw.trim()
            if (line.startsWith("E: ")) element = line.removePrefix("E: ").substringBefore(' ')
            if (!line.startsWith("A: ")) continue
            val attr =
                line
                    .removePrefix("A: ")
                    .substringBefore('(')
                    .substringBefore('=')
                    .substringAfterLast(':')
            val value = Regex("""="([^"]*)"""").find(line)?.groupValues?.get(1) ?: line.substringAfter('=', "")
            val isTrue = value.startsWith("true") || value.startsWith("(type 0x12)0xffffffff")
            when {
                attr == "debuggable" && isTrue -> {
                    out += "manifest: android:debuggable is true"
                }

                attr == "testOnly" && isTrue -> {
                    out += "manifest: android:testOnly is true"
                }

                attr == "name" && element in COMPONENTS && TEST_ONLY_NAME.containsMatchIn(value) -> {
                    out += "manifest: test-only component <$element> $value"
                }
            }
        }
        return out
    }

    private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider", "instrumentation")

    /** Type descriptors of test frameworks and our test-only modules (T-PRIV-02's class scan, plus test code). */
    private val TEST_DESCRIPTORS =
        listOf(
            "Lorg/junit/",
            "Landroidx/test/",
            "Lorg/robolectric/",
            "Lleakcanary/",
            "Lapp/strata/launcher/testing/",
            "Lcom/google/common/truth/",
        )

    /** Known tracker / network SDK packages (T-PRIV-02 "APK class scan for known SDK packages"). */
    private val TRACKER_DESCRIPTORS =
        listOf(
            "Lcom/google/firebase/",
            "Lcom/google/android/gms/",
            "Lcom/android/billingclient/",
            "Lokhttp3/",
            "Lretrofit2/",
            "Lio/ktor/client/",
            "Lio/sentry/",
            "Lcom/bugsnag/",
            "Lcom/crashlytics/",
            "Lcom/facebook/",
            "Lcom/appsflyer/",
            "Lcom/adjust/sdk/",
            "Lcom/mixpanel/",
            "Lcom/amplitude/",
        )

    /** [dexStrings] is the dex files' bytes read as ISO-8859-1, so descriptors appear verbatim. */
    fun dexViolations(dexStrings: String): List<String> {
        val out = mutableListOf<String>()
        TEST_DESCRIPTORS.filter { it in dexStrings }.forEach { out += "release dex contains test code: $it" }
        TRACKER_DESCRIPTORS.filter { it in dexStrings }.forEach {
            out +=
                "release dex contains a tracker/network SDK: $it"
        }
        return out
    }

    /**
     * R8 mapping: a protobuf-lite message (a class with a `DEFAULT_INSTANCE` field) must keep its `name_` field names,
     * because the lite runtime finds them by reflection (see app/proguard-rules.pro).
     */
    fun protoRenameViolations(mapping: String): List<String> {
        val out = mutableListOf<String>()
        var cls: String? = null
        val fields = mutableListOf<Pair<String, String>>()

        fun flush() {
            val c = cls ?: return
            if (fields.any { it.first == "DEFAULT_INSTANCE" }) {
                fields.filter { it.first.endsWith("_") && it.first != it.second }.forEach { (from, to) ->
                    out += "protobuf-lite field $c.$from renamed to $to: the lite runtime finds it by name and fails"
                }
            }
        }
        val classLine = Regex("""^(\S+) -> (\S+):$""")
        val fieldLine = Regex("""^\s+\S+ ([A-Za-z_$][\w$]*) -> ([\w$]+)$""")
        for (line in mapping.lineSequence()) {
            if (line.startsWith("#") || line.trimStart().startsWith("#")) continue
            val c = classLine.find(line)
            if (c != null) {
                flush()
                cls = c.groupValues[1]
                fields.clear()
                continue
            }
            fieldLine.find(line)?.let { fields += it.groupValues[1] to it.groupValues[2] }
        }
        flush()
        return out
    }
}
