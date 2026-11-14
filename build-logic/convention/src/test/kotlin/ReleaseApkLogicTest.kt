import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `checkReleaseApk` rules (T-CI-07, T-PRIV-02/03 on the APK): each fails with a clear message on a violation. */
class ReleaseApkLogicTest {
    private val approved =
        setOf(
            "android.permission.ACCESS_HIDDEN_PROFILES",
            "android.permission.READ_CONTACTS",
            "android.permission.REQUEST_DELETE_PACKAGES",
        )
    private val own = "app.strata.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    private fun e(name: String, compressed: Long, size: Long = compressed) =
        ReleaseApkLogic.Entry(
            name,
            compressed,
            size,
        )

    private fun badging(vararg extra: String): String =
        (
            listOf(
                "package: name='app.strata.launcher' versionCode='2'",
                "uses-permission: name='android.permission.REQUEST_DELETE_PACKAGES'",
                "uses-permission: name='android.permission.ACCESS_HIDDEN_PROFILES'",
                "uses-permission: name='android.permission.READ_CONTACTS'",
                "uses-permission: name='$own'",
                "locales: '--_--' 'ar' 'de'",
            ) + extra
        ).joinToString("\n")

    // ---- size (T-CI-07) ----

    @Test
    fun `an APK without native code is measured once`() {
        assertThat(ReleaseApkLogic.perAbiSizes(2_000_000, listOf(e("classes.dex", 1_000_000))))
            .containsExactly("universal", 2_000_000L)
    }

    @Test
    fun `per-ABI size subtracts the other ABIs' libraries`() {
        val entries =
            listOf(
                e("lib/arm64-v8a/a.so", 100),
                e("lib/arm64-v8a/b.so", 50),
                e("lib/x86_64/a.so", 200),
                e("classes.dex", 1_000),
            )
        assertThat(ReleaseApkLogic.perAbiSizes(10_000, entries))
            .containsExactly("arm64-v8a", 9_800L, "x86_64", 9_850L)
    }

    @Test
    fun `an ABI over 12 MB fails and small ones pass`() {
        val entries = listOf(e("lib/arm64-v8a/big.so", 3_000_000), e("lib/x86/small.so", 10))
        val problems = ReleaseApkLogic.sizeViolations("app.apk", 12_500_000, entries)
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("app.apk (arm64-v8a)")
        assertThat(ReleaseApkLogic.sizeViolations("app.apk", 2_000_000, entries)).isEmpty()
    }

    @Test
    fun `fonts count by extension, even under shortened resource names, and over 3 MB fail`() {
        val entries = listOf(e("res/-a.ttf", 1_000_000, 2_000_000), e("assets/fonts/x.OTF", 900_000, 1_500_000))
        assertThat(ReleaseApkLogic.fontBytes(entries)).isEqualTo(3_500_000)
        assertThat(ReleaseApkLogic.sizeViolations("app.apk", 5_000_000, entries).single()).contains("fonts")
    }

    // ---- badging ----

    @Test
    fun `clean badging passes`() {
        assertThat(ReleaseApkLogic.badgingViolations(badging(), approved, setOf(own))).isEmpty()
    }

    @Test
    fun `debuggable, testOnly and pseudo-locales fail`() {
        val b =
            badging("application-debuggable", "testOnly='-1'")
                .replace("locales: '--_--' 'ar' 'de'", "locales: '--_--' 'de' 'en-XA' 'ar-XB'")
        val problems = ReleaseApkLogic.badgingViolations(b, approved, setOf(own)).joinToString("\n")
        assertThat(problems).contains("debuggable")
        assertThat(problems).contains("testOnly")
        assertThat(problems).contains("en-XA")
        assertThat(problems).contains("ar-XB")
    }

    @Test
    fun `an extra or forbidden permission in the APK fails`() {
        val b =
            badging(
                "uses-permission: name='android.permission.INTERNET'",
                "uses-permission: name='android.permission.CAMERA'",
            )
        val problems = ReleaseApkLogic.badgingViolations(b, approved, setOf(own)).joinToString("\n")
        assertThat(problems).contains("forbidden permission(s): [android.permission.INTERNET]")
        assertThat(problems).contains("android.permission.CAMERA")
    }

    // ---- manifest ----

    private val ns = "http://schemas.android.com/apk/res/android"

    private fun nameAttr(value: String) = "A: $ns:name(0x01010003)=\"$value\" (Raw: \"$value\")"

    private val cleanTree =
        listOf(
            "N: android=$ns (line=2)",
            "  E: manifest (line=2)",
            "    E: application (line=40)",
            "      A: $ns:allowBackup(0x01010280)=true",
            "      E: activity (line=55)",
            "        " + nameAttr("app.strata.launcher.home.HomeActivity"),
        ).joinToString("\n")

    @Test
    fun `the release manifest passes`() {
        assertThat(ReleaseApkLogic.manifestViolations(cleanTree)).isEmpty()
    }

    @Test
    fun `debuggable, testOnly and test components fail`() {
        val tree =
            listOf(
                cleanTree,
                "      A: $ns:debuggable(0x0101000f)=true",
                "      A: $ns:testOnly(0x01010272)=true",
                "      E: receiver (line=90)",
                "        " + nameAttr("app.strata.launcher.widget.FixtureWidgetProvider"),
                "      E: activity (line=95)",
                "        " + nameAttr("androidx.test.core.app.InstrumentationActivityInvoker"),
            ).joinToString("\n")
        val problems = ReleaseApkLogic.manifestViolations(tree)
        assertThat(problems).hasSize(4)
        assertThat(problems.joinToString()).contains("FixtureWidgetProvider")
    }

    // ---- dex ----

    @Test
    fun `test code and tracker SDKs in dex fail, our own classes pass`() {
        assertThat(
            ReleaseApkLogic.dexViolations("Lapp/strata/launcher/home/HomeActivity;\u0000Lkotlin/Unit;"),
        ).isEmpty()
        val problems = ReleaseApkLogic.dexViolations("Lorg/junit/Assert;\u0000Lcom/google/firebase/FirebaseApp;")
        assertThat(problems).hasSize(2)
    }

    // ---- R8 mapping (protobuf-lite reflection) ----

    @Test
    fun `renamed protobuf-lite fields fail, kept names and plain classes pass`() {
        val mapping =
            """
            # compiler: R8
            app.strata.launcher.core.data.settings.Settings -> rm2:
            # {"id":"sourceFile","fileName":"Settings.java"}
                app.strata.launcher.core.data.settings.Settings DEFAULT_INSTANCE -> C
                boolean onboardingDone_ -> h
                int bitField0_ -> bitField0_
                1:5:void <clinit>():1961:1961 -> <clinit>
            app.strata.launcher.Other -> a:
                int count_ -> b
            """.trimIndent()
        val problems = ReleaseApkLogic.protoRenameViolations(mapping)
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).contains("Settings.onboardingDone_ renamed to h")
        assertThat(
            ReleaseApkLogic.protoRenameViolations(
                mapping.replace("onboardingDone_ -> h", "onboardingDone_ -> onboardingDone_"),
            ),
        ).isEmpty()
    }
}
