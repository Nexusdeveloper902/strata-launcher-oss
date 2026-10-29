import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Negative and positive checks for every CI gate: each must fail with a clear message on a violation. */
class GateLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val approved =
        setOf(
            "android.permission.ACCESS_HIDDEN_PROFILES",
            "android.permission.READ_CONTACTS",
            "android.permission.REQUEST_DELETE_PACKAGES",
        )
    private val ownSignature = "app.strata.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    private fun manifest(vararg tags: String): java.io.File =
        tmp.newFile().apply {
            writeText(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">
               ${tags.joinToString("\n")}
               <application/></manifest>""",
            )
        }

    private fun use(name: String) = """<uses-permission android:name="$name"/>"""

    // T-CI-01 / T-PRIV-03
    @Test
    fun `exact approved set passes and the androidx signature permission is ignored`() {
        val m =
            manifest(
                *approved.map(::use).toTypedArray(),
                """<permission android:name="$ownSignature" android:protectionLevel="signature"/>""",
                use(ownSignature),
            )
        val found = GateLogic.readManifestPermissions(m)
        assertThat(GateLogic.permissionViolations(found, approved, setOf(ownSignature))).isEmpty()
    }

    @Test
    fun `INTERNET fails and is named`() {
        val m = manifest(*approved.map(::use).toTypedArray(), use("android.permission.INTERNET"))
        val problems = GateLogic.permissionViolations(GateLogic.readManifestPermissions(m), approved, emptySet())
        assertThat(problems.single()).contains("Forbidden permission")
        assertThat(problems.single()).contains("android.permission.INTERNET")
    }

    @Test
    fun `unknown extra and missing approved permissions fail`() {
        val m = manifest(use("android.permission.READ_CONTACTS"), use("android.permission.CAMERA"))
        val problems = GateLogic.permissionViolations(GateLogic.readManifestPermissions(m), approved, emptySet())
        assertThat(problems.joinToString()).contains("android.permission.CAMERA")
        assertThat(problems.joinToString()).contains("missing")
    }

    @Test
    fun `sdk-23 variants are counted and other custom permissions fail`() {
        val m =
            manifest(
                *approved.map(::use).toTypedArray(),
                """<uses-permission-sdk-23 android:name="android.permission.QUERY_ALL_PACKAGES"/>""",
                """<permission android:name="evil.PERM"/>""",
            )
        val problems = GateLogic.permissionViolations(GateLogic.readManifestPermissions(m), approved, emptySet())
        assertThat(problems.joinToString()).contains("QUERY_ALL_PACKAGES")
        assertThat(problems.joinToString()).contains("evil.PERM")
    }

    // T-CI-02 / T-PRIV-02
    private val allow = GateLogic.parseList("androidx.*\norg.jetbrains.kotlin:*\ncom.squareup.okio:okio # io only")
    private val deny = GateLogic.parseList("com.squareup.okhttp3:*\ncom.google.firebase.*\nio.ktor.*")

    @Test
    fun `allow-listed artifacts pass`() {
        val ok = setOf("androidx.core:core", "org.jetbrains.kotlin:kotlin-stdlib", "com.squareup.okio:okio")
        assertThat(GateLogic.dependencyViolations(ok, allow, deny, "cfg")).isEmpty()
    }

    @Test
    fun `network and analytics libraries are forbidden`() {
        val bad =
            setOf("com.squareup.okhttp3:okhttp", "com.google.firebase.crashlytics:x", "io.ktor.client:ktor-client-core")
        val problems = GateLogic.dependencyViolations(bad, allow + "com.google.firebase.*", deny, "cfg")
        assertThat(problems).hasSize(3)
        assertThat(problems.all { it.startsWith("FORBIDDEN") }).isTrue()
    }

    @Test
    fun `unlisted artifact fails`() {
        val problems = GateLogic.dependencyViolations(setOf("com.example:thing"), allow, deny, "cfg")
        assertThat(problems.single()).contains("Unlisted dependency com.example:thing")
    }

    @Test
    fun `group prefix does not match a lookalike group`() {
        assertThat(GateLogic.matchesForbidden("androidx.*", "androidxevil", "a")).isFalse()
        assertThat(GateLogic.matchesForbidden("androidx.*", "androidx.core", "a")).isTrue()
        assertThat(GateLogic.matchesForbidden("androidx.*", "androidx", "a")).isTrue()
    }

    // T-CI-03
    @Test
    fun `feature to platform edge fails`() {
        val p = GateLogic.moduleGraphViolations(":feature:home", true, setOf(":platform:apps", ":core:ui"), emptySet())
        assertThat(p.single()).contains(":feature:home -> :platform:apps")
    }

    @Test
    fun `feature to feature and backup edges fail`() {
        val p = GateLogic.moduleGraphViolations(":feature:home", true, setOf(":feature:search", ":backup"), emptySet())
        assertThat(p).hasSize(2)
    }

    @Test
    fun `platform to platform and platform to data fail`() {
        val p =
            GateLogic.moduleGraphViolations(
                ":platform:apps",
                true,
                setOf(":platform:icons", ":core:data", ":core:model"),
                emptySet(),
            )
        assertThat(p).hasSize(2)
    }

    @Test
    fun `theme must not be Android or depend on data`() {
        val p = GateLogic.moduleGraphViolations(":core:theme", true, setOf(":core:data", ":core:json"), emptySet())
        assertThat(p).hasSize(2)
        assertThat(p.joinToString()).contains("pure Kotlin")
    }

    @Test
    fun `core ui must not depend on data or platform`() {
        val p =
            GateLogic.moduleGraphViolations(
                ":core:ui",
                true,
                setOf(":core:data", ":platform:system", ":core:theme"),
                emptySet(),
            )
        assertThat(p).hasSize(2)
    }

    @Test
    fun `common model json may use only stdlib and coroutines core`() {
        val p =
            GateLogic.moduleGraphViolations(
                ":core:model",
                false,
                setOf(":core:common"),
                setOf(
                    "org.jetbrains.kotlin:kotlin-stdlib",
                    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                    "androidx.core:core",
                ),
            )
        assertThat(p.single()).contains("androidx.core:core")
    }

    @Test
    fun `testing modules may not use platform modules and app is unrestricted`() {
        assertThat(
            GateLogic.moduleGraphViolations(":testing:fakes", false, setOf(":platform:apps"), emptySet()),
        ).hasSize(1)
        assertThat(
            GateLogic.moduleGraphViolations(
                ":app",
                true,
                setOf(":platform:system", ":feature:home", ":backup"),
                emptySet(),
            ),
        ).isEmpty()
    }

    // T-CI-08
    @Test
    fun `entitlement symbols are found and isProfile is not`() {
        val src =
            listOf(
                "val a = 1",
                "fun isPro() = true",
                "val Premium = 2",
                "class BillingClient",
                "val isProfile = 1",
                "val x = 7 // purchase",
            ).joinToString("\n")
        val hits = GateLogic.entitlementViolations("F.kt", src)
        assertThat(
            hits.map { it.substringBefore(": entitlement") },
        ).containsExactly("F.kt:2", "F.kt:3", "F.kt:4", "F.kt:6")
    }
}
