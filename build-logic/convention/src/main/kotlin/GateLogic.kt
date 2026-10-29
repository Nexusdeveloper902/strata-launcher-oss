import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure logic behind the CI gates (T-CI-01…03, T-CI-08, T-PRIV-02/03). Kept free of Gradle types so
 * that `build-logic/convention/src/test` can prove each gate fails on a violation.
 */
internal object GateLogic {
    // ---- T-CI-01 / T-PRIV-03: merged-manifest permissions --------------------------------------

    /** Permissions that must never appear (named in the failure message to make the cause obvious). */
    val FORBIDDEN_PERMISSIONS: Set<String> =
        setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.BIND_APPWIDGET",
            "android.permission.EXPAND_STATUS_BAR",
            "com.google.android.gms.permission.AD_ID",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SET_WALLPAPER",
        )

    /** `uses-permission*` and `permission` names from a merged manifest. */
    class ManifestPermissions(val used: Set<String>, val declared: Set<String>)

    fun readManifestPermissions(manifest: File): ManifestPermissions {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = factory.newDocumentBuilder().parse(manifest)
        val used = linkedSetOf<String>()
        val declared = linkedSetOf<String>()
        val root = doc.documentElement.childNodes
        for (i in 0 until root.length) {
            val node = root.item(i)
            val name = node.attributes?.getNamedItemNS(ANDROID_NS, "name")?.nodeValue ?: continue
            when {
                node.nodeName.startsWith("uses-permission") -> used += name
                node.nodeName == "permission" -> declared += name
            }
        }
        return ManifestPermissions(used, declared)
    }

    fun parseList(text: String): Set<String> =
        text
            .lineSequence()
            .map {
                it
                    .substringBefore(
                        '#',
                    ).trim()
            }.filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())

    /**
     * @param ignored the app's own androidx-generated signature permission
     *   (`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), which is not a system permission.
     */
    fun permissionViolations(found: ManifestPermissions, approved: Set<String>, ignored: Set<String>): List<String> {
        val out = mutableListOf<String>()
        val used = found.used - ignored
        val forbidden = used.intersect(FORBIDDEN_PERMISSIONS)
        if (forbidden.isNotEmpty()) {
            out +=
                "Forbidden permission(s) in the merged release manifest: ${forbidden.sorted()}"
        }
        val extra = used - approved - forbidden
        if (extra.isNotEmpty()) out += "Permission(s) not in config/approved-permissions.txt: ${extra.sorted()}"
        val missing = approved - used
        if (missing.isNotEmpty()) out += "Approved permission(s) missing from the merged manifest: ${missing.sorted()}"
        val customDeclared = found.declared - ignored
        if (customDeclared.isNotEmpty()) out += "Unexpected <permission> declaration(s): ${customDeclared.sorted()}"
        return out
    }

    // ---- T-CI-02 / T-PRIV-02: dependency allow-list --------------------------------------------

    /** `group:name`, `group:*` or a bare `group` (whole group). */
    fun matchesEntry(entry: String, group: String, name: String): Boolean {
        val g = entry.substringBefore(':')
        val n = if (':' in entry) entry.substringAfter(':') else "*"
        return g == group && (n == "*" || n == name)
    }

    /** Used for both lists. Group-prefix entries end with `.*` (for example `com.google.firebase.*`). */
    fun matchesForbidden(entry: String, group: String, name: String): Boolean {
        if (entry.endsWith(".*") && ':' !in entry) {
            val prefix = entry.removeSuffix(".*")
            return group == prefix || group.startsWith("$prefix.")
        }
        return matchesEntry(entry, group, name)
    }

    fun dependencyViolations(
        resolved: Set<String>,
        allowed: Set<String>,
        forbidden: Set<String>,
        configurationName: String,
    ): List<String> {
        val out = mutableListOf<String>()
        for (id in resolved.sorted()) {
            val group = id.substringBefore(':')
            val name = id.substringAfter(':')
            val bad = forbidden.firstOrNull { matchesForbidden(it, group, name) }
            when {
                bad != null -> {
                    out +=
                        "FORBIDDEN dependency $id on $configurationName (matches '$bad': network, analytics, ads or billing code is not allowed, NFR-PRIV-01/02)"
                }

                allowed.none { matchesForbidden(it, group, name) } -> {
                    out +=
                        "Unlisted dependency $id on $configurationName. Add it to config/dependency-allowlist.txt only if it is in spec/ARCHITECTURE.md section 15"
                }
            }
        }
        return out
    }

    // ---- T-CI-03: module graph (ARCHITECTURE §2.1) ---------------------------------------------

    private val CORE_PURE =
        setOf(":core:common", ":core:model", ":core:json", ":core:platform-api", ":core:theme", ":core:search")
    private val PURE_KOTLIN_MODULES = CORE_PURE

    /** Modules that must be pure Kotlin/JVM (no Android plugin, no Android SDK). */
    fun mustBePureJvm(path: String): Boolean = path in PURE_KOTLIN_MODULES || path.startsWith(":testing:")

    /** Projects a module may depend on (non-test configurations). `null` means unrestricted (`:app`). */
    private fun allowedProjects(path: String): ((String) -> Boolean)? {
        fun anyOf(vararg allowed: String): (String) -> Boolean = { it in allowed }
        return when {
            path == ":core:common" -> {
                anyOf()
            }

            path == ":core:model" || path == ":core:json" -> {
                anyOf(":core:common")
            }

            path == ":core:platform-api" -> {
                anyOf(":core:model", ":core:common")
            }

            path == ":core:theme" || path == ":core:search" -> {
                anyOf(":core:model", ":core:common", ":core:json")
            }

            path == ":core:data" -> {
                { dep -> dep in CORE_PURE }
            }

            path == ":core:ui" -> {
                anyOf(":core:model", ":core:theme")
            }

            path.startsWith(":platform:") -> {
                anyOf(":core:platform-api", ":core:model", ":core:common")
            }

            path.startsWith(":feature:") -> {
                { dep -> dep.startsWith(":core:") }
            }

            path == ":backup" -> {
                { dep -> dep.startsWith(":core:") }
            }

            path.startsWith(":testing:") -> {
                { dep -> dep.startsWith(":core:") || dep.startsWith(":testing:") }
            }

            path == ":app" || path == ":benchmark" -> {
                null
            }

            else -> {
                null
            }
        }
    }

    private val STDLIB_ONLY_MODULES = setOf(":core:common", ":core:model", ":core:json")

    fun moduleGraphViolations(
        path: String,
        isAndroid: Boolean,
        projectDeps: Set<String>,
        externalDeps: Set<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        if (mustBePureJvm(path) && isAndroid) {
            out += "$path applies an Android plugin but must be pure Kotlin (ARCHITECTURE 2.1: no Android SDK)"
        }
        val rule = allowedProjects(path)
        if (rule != null) {
            for (dep in projectDeps.sorted()) {
                if (!rule(dep)) out += "$path -> $dep is not allowed by ARCHITECTURE 2.1"
            }
        }
        if (path in STDLIB_ONLY_MODULES) {
            for (dep in externalDeps.sorted()) {
                val ok =
                    dep.startsWith("org.jetbrains.kotlin:") || dep == "org.jetbrains.kotlinx:kotlinx-coroutines-core" ||
                        dep.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core-")
                if (!ok) {
                    out +=
                        "$path -> $dep: this module may use only the Kotlin stdlib and coroutines-core (ARCHITECTURE 2.1)"
                }
            }
        }
        return out
    }

    // ---- T-CI-08: entitlement-free source ------------------------------------------------------

    /** `isPro` only as a whole identifier prefix, so `isProfile` stays legal. */
    val ENTITLEMENT_REGEX: Regex = Regex("""\bisPro(?![a-z])|(?i:premium|entitle|purchase|billing)""")

    fun entitlementViolations(label: String, text: String): List<String> =
        text
            .lineSequence()
            .withIndex()
            .mapNotNull { (i, line) ->
                ENTITLEMENT_REGEX.find(line)?.let {
                    "$label:${i + 1}: entitlement symbol '${it.value}' (FR-THEME-01: everything is free, D-004)"
                }
            }.toList()

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
