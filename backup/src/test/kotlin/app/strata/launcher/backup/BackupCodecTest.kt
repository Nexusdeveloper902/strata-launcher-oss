package app.strata.launcher.backup

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.testing.fixtures.BackupFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupCodecTest {
    private fun ok(bytes: ByteArray): BackupCodec.Decoded {
        val r = BackupCodec.decode(bytes)
        assertThat(r).isInstanceOf(Outcome.Ok::class.java)
        return (r as Outcome.Ok).value
    }

    private fun err(bytes: ByteArray): BackupError = (BackupCodec.decode(bytes) as Outcome.Err).error

    @Test
    fun validFixture_decodesEveryClass() {
        val d = ok(BackupFixtures.validBytes())
        val doc = d.document
        assertThat(doc.settings!!.listTwoColumn).isFalse()
        assertThat(doc.settings!!.badgeStyle).isEqualTo(BadgeStyle.COUNT)
        assertThat(doc.settings!!.appearanceOverride).isEqualTo(AppearanceSetting.DARK)
        // Absent exported fields are the catalogue defaults.
        assertThat(doc.settings!!.enforceContrast).isTrue()
        assertThat(doc.settingsCount).isEqualTo(4)
        assertThat(doc.apps).hasSize(3)
        assertThat(doc.apps[0].label).isEqualTo("Nav")
        assertThat(doc.apps[0].icon).isEqualTo(IconOverride("com.example.iconpack", "maps"))
        assertThat(doc.apps[1].icon).isNull()
        assertThat(doc.favorites.map { it.component })
            .containsExactly("com.example.maps/.MainActivity", "com.example.missing/.Main")
            .inOrder()
        assertThat(doc.widgets.single().heightStep).isEqualTo(2)
        assertThat(doc.themes!!.embedded.single()).isInstanceOf(EmbeddedTheme.Valid::class.java)
        assertThat(d.stats).isEqualTo(BackupStats())
    }

    @Test
    fun tBackup05_newerMajorIsRejected() {
        assertThat(err(BackupFixtures.newerMajor())).isEqualTo(BackupError.NewerVersion)
    }

    @Test
    fun tBackup06_wrongTypeCorruptOversize_eachHasItsOwnError() {
        assertThat(err(BackupFixtures.themeFile())).isEqualTo(BackupError.NotABackup)
        assertThat(err(byteArrayOf(0x50, 0x4B, 0x03, 0x04))).isEqualTo(BackupError.NotABackup) // a zip
        assertThat(err(BackupFixtures.truncated())).isInstanceOf(BackupError.Damaged::class.java)
        val trunc = err(BackupFixtures.truncated()) as BackupError.Damaged
        assertThat(trunc.line).isGreaterThan(1)
        assertThat(err(BackupFixtures.oversize())).isEqualTo(BackupError.TooLarge)
        val dup = err(BackupFixtures.duplicateKeys()) as BackupError.Damaged
        assertThat(dup.line).isEqualTo(5)
        assertThat(err(BackupFixtures.invalidUtf8())).isEqualTo(BackupError.Damaged(0, 0))
        assertThat(err("[]".toByteArray())).isEqualTo(BackupError.NotABackup)
        assertThat(err("""{"format":"strata-backup"}""".toByteArray())).isEqualTo(BackupError.NotABackup)
    }

    @Test
    fun tBackup07_newerMinor_unknownKeysCountedKnownKept() {
        val d = ok(BackupFixtures.newerMinor())
        assertThat(d.stats.unknownKeys).isEqualTo(3)
        assertThat(d.document.formatMinor).isEqualTo(4)
        assertThat(d.document.settings!!.hapticsEnabled).isFalse()
        assertThat(d.document.apps).hasSize(3)
    }

    @Test
    fun tBackup09_invalidEmbeddedThemeIsExcludedNotFatal() {
        val d = ok(BackupFixtures.mixedThemes())
        val embedded = d.document.themes!!.embedded
        assertThat(embedded[0]).isInstanceOf(EmbeddedTheme.Valid::class.java)
        assertThat(embedded[1]).isEqualTo(EmbeddedTheme.Invalid("Broken"))
    }

    @Test
    fun newerThemeSchema_isKeptAsNeedsUpdate() {
        val embedded = ok(BackupFixtures.newerTheme()).document.themes!!.embedded
        assertThat((embedded[1] as EmbeddedTheme.Newer).id).isEqualTo(BackupFixtures.THEME_B)
    }

    @Test
    fun hostileEntries_areDroppedAndCounted() {
        val json =
            """
            {"format":"strata-backup","formatVersion":1,
             "contents":{"workProfile":false},
             "apps":[
               {"component":"com.a/.A","profile":"private","hidden":true},
               {"component":"com.a/.A","profile":"work","hidden":true},
               {"component":"../../etc/passwd","profile":"main","hidden":true},
               {"component":"com.a/.A","profile":"main","label":"${"x".repeat(61)}"},
               {"component":"com.a/.A","profile":"main","hidden":"yes"},
               {"component":"com.a/.A","profile":"main","icon":{"kind":"pack","pack":"com.p","drawable":"../x"}},
               {"component":"com.a/.A","profile":"main","hidden":true},
               {"component":"com.a/.A","profile":"main","hidden":false}
             ],
             "favorites":[${(1..13).joinToString(",") { """{"component":"com.f$it/.F","profile":"main"}""" }}],
             "launchLog":[{"component":"com.a/.A","profile":"main","day":"2026-10-28","count":3}]
            }
            """.trimIndent()
        val d = ok(json.toByteArray())
        assertThat(d.document.apps).hasSize(1)
        assertThat(
            d.document.apps
                .single()
                .hidden,
        ).isTrue()
        assertThat(d.document.favorites).hasSize(12)
        assertThat(d.document.launchLog).isEmpty()
        // 7 apps + 1 favourite over the cap + the launch log without its opt-in.
        assertThat(d.stats.dropped).isEqualTo(9)
    }

    @Test
    fun roundTrip_encodeThenDecodeIsStable() {
        val d = ok(BackupFixtures.validBytes()).document
        val again = ok(BackupCodec.encode(d)).document
        assertThat(again.copy(settingsCount = 0)).isEqualTo(d.copy(settingsCount = 0))
        assertThat(BackupCodec.toJson(d).keys).doesNotContain("launchLog")
    }

    @Test
    fun settingsCodec_writesOnlyExportedRows() {
        val keys = SettingsCodec.encode(Settings(installId = "x", onboardingDone = true)).keys
        assertThat(keys).containsNoneOf(
            "install_id",
            "onboarding_done",
            "catalogue_version",
            "contacts_hint_shown",
            "default_home_banner_dismissed_until",
            "private_space_container_hidden",
        )
        assertThat(keys).hasSize(17)
    }

    @Test
    fun restoreStateCodec_isRejectedAsABackup() {
        val bytes =
            RestoreStateCodec.encode(
                RestoreState(
                    Settings.DEFAULT,
                    app.strata.launcher.core.platformapi.backup
                        .AppData(),
                    app.strata.launcher.core.theme.store
                        .ThemeRestoreTarget(emptyList(), null, null, true),
                ),
            )
        assertThat(err(bytes)).isEqualTo(BackupError.NotABackup)
        assertThat(RestoreStateCodec.decode(bytes)).isNotNull()
    }
}
