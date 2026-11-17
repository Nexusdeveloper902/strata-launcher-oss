package app.strata.launcher.core.data.settings

import app.strata.launcher.core.model.AppearanceSetting
import app.strata.launcher.core.model.BadgeStyle
import app.strata.launcher.core.model.OnboardingProgress
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.testing.fakes.FakeResetNotices
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random
import app.strata.launcher.core.data.settings.Settings as ProtoSettings

/** `settings.pb` (ARCHITECTURE §4.2): defaults, sparse writes, M0 migration, install id, corruption (T-LIFE-12). */
class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val notices = FakeResetNotices()
    private val file get() = File(tmp.root, DataStoreSettingsRepository.FILE_NAME)
    private val legacy get() = File(tmp.root, DataStoreSettingsRepository.LEGACY_ENTRY_FLAGS)

    private fun <T> withRepo(block: suspend (DataStoreSettingsRepository) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            return runBlocking {
                block(
                    DataStoreSettingsRepository.create(
                        scope,
                        notices,
                        legacyFlags = { legacy },
                        produceFile = { file },
                    ),
                )
            }
        } finally {
            // cancel() alone returns before DataStore releases its per-file registration, so the next
            // withRepo on the same file could fail with "multiple DataStores active". Wait for it.
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }

    private fun proto(): ProtoSettings = file.inputStream().use { ProtoSettings.parseFrom(it) }

    @Test
    fun freshInstall_catalogueDefaults_andInstallIdV4() {
        val s = withRepo { it.settings.first() }
        assertThat(s.copy(installId = "")).isEqualTo(Settings.DEFAULT)
        assertThat(SettingsMapper.isUuidV4(s.installId)).isTrue()
        assertThat(notices.causes.value).isEmpty()
    }

    @Test
    fun installIdIsStableAcrossInstances() {
        val first = withRepo { it.settings.first().installId }
        val second = withRepo { it.settings.first().installId }
        assertThat(second).isEqualTo(first)
    }

    /** Untouched fields stay absent, so a later default change reaches them without a migration. */
    @Test
    fun updatesWriteOnlyChangedFields() {
        withRepo { repo -> repo.update { it.copy(hapticsEnabled = false, badgeStyle = BadgeStyle.COUNT) } }
        val p = proto()
        assertThat(p.hasHapticsEnabled()).isTrue()
        assertThat(p.hapticsEnabled).isFalse()
        assertThat(p.hasBadgeStyle()).isTrue()
        assertThat(p.hasListTwoColumn()).isFalse()
        assertThat(p.hasOnboardingDone()).isFalse()
        val s = withRepo { it.settings.first() }
        assertThat(s.hapticsEnabled).isFalse()
        assertThat(s.badgeStyle).isEqualTo(BadgeStyle.COUNT)
        assertThat(s.listTwoColumn).isTrue()
    }

    @Test
    fun everyFieldRoundTrips() {
        val custom =
            Settings(
                onboardingDone = true,
                listTwoColumn = false,
                hapticsEnabled = false,
                showHiddenInSearch = false,
                recentsEnabled = false,
                launchLogPaused = true,
                homePressOpensSearch = true,
                allowRotation = true,
                hideStatusBar = true,
                keepBackGestureOnIndexSide = true,
                badgesEnabled = true,
                badgeStyle = BadgeStyle.COUNT,
                contactsSearchEnabled = true,
                contactsHintShown = true,
                appearanceOverride = AppearanceSetting.DARK,
                enforceContrast = false,
                defaultHomeBannerDismissedUntil = 1234L,
                privateSpaceContainerHidden = true,
                listSortByUsage = true,
                lockGestureEnabled = true,
                onboardingProgress = OnboardingProgress.ROLE_REQUESTED,
            )
        val id =
            withRepo { repo ->
                repo.update { custom.copy(installId = it.installId) }.let { repo.settings.first().installId }
            }
        assertThat(withRepo { it.settings.first() }).isEqualTo(custom.copy(installId = id))
    }

    /** Out-of-range values read as the default or are clamped (§4.2). */
    @Test
    fun outOfRangeValuesAreClampedOnRead() {
        file.parentFile.mkdirs()
        file.outputStream().use {
            ProtoSettings
                .newBuilder()
                .setCatalogueVersion(-5)
                .setDefaultHomeBannerDismissedUntil(-1)
                .setInstallId("not-a-uuid")
                .setBadgeStyleValue(42)
                .build()
                .writeTo(it)
        }
        val s = withRepo { it.settings.first() }
        assertThat(s.catalogueVersion).isEqualTo(1)
        assertThat(s.defaultHomeBannerDismissedUntil).isEqualTo(0L)
        assertThat(s.badgeStyle).isEqualTo(BadgeStyle.DOT)
        assertThat(SettingsMapper.isUuidV4(s.installId)).isTrue() // replaced by the migration
    }

    /** IMPLEMENTATION_ROADMAP M1: `onboarding_done` moves from the M0 file into field 3; the file is deleted. */
    @Test
    fun m0OnboardingFlagIsMigratedAndLegacyFileDeleted() {
        legacy.writeBytes(byteArrayOf(1, 1))
        assertThat(withRepo { it.settings.first().onboardingDone }).isTrue()
        assertThat(legacy.exists()).isFalse()
        assertThat(proto().onboardingDone).isTrue()
    }

    @Test
    fun m0FlagFalseOrCorrupt_migratesToDefault() {
        legacy.writeBytes(byteArrayOf(1, 0))
        assertThat(withRepo { it.settings.first().onboardingDone }).isFalse()
        assertThat(legacy.exists()).isFalse()
        legacy.writeText("garbage")
        assertThat(withRepo { it.settings.first().onboardingDone }).isFalse()
        assertThat(legacy.exists()).isFalse()
    }

    /** T-LIFE-12: random bytes in settings.pb → defaults with the banner, no crash. */
    @Test
    fun tLife12_corruptFileGivesDefaultsAndBanner() {
        file.parentFile.mkdirs()
        // Seeded bytes that are not a valid protobuf message (an invalid wire type early on).
        file.writeBytes(byteArrayOf(0x0F, 0x7F) + Random(12).nextBytes(254))
        val s = withRepo { it.settings.first() }
        assertThat(s.copy(installId = "")).isEqualTo(Settings.DEFAULT)
        assertThat(notices.causes.value).containsExactly(ResetCause.SETTINGS_CORRUPT)
        // The replaced file is valid again.
        assertThat(proto().hasInstallId()).isTrue()
    }
}
