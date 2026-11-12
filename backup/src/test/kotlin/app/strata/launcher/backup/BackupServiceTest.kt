package app.strata.launcher.backup

import app.strata.launcher.backup.BackupHarness.Companion.MAIN
import app.strata.launcher.backup.BackupHarness.Companion.PRIVATE
import app.strata.launcher.backup.BackupHarness.Companion.WORK
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.json.JsonArray
import app.strata.launcher.core.json.JsonObject
import app.strata.launcher.core.json.JsonParser
import app.strata.launcher.core.json.JsonString
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.IconOverride
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.model.WidgetPlacement
import app.strata.launcher.core.model.WidgetPlacementState
import app.strata.launcher.core.model.WidgetProviderRef
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.platformapi.backup.BackupError
import app.strata.launcher.core.platformapi.backup.ExportOptions
import app.strata.launcher.core.platformapi.backup.RestorePreview
import app.strata.launcher.core.theme.store.StoredThemeFile
import app.strata.launcher.testing.fixtures.BackupFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackupServiceTest {
    private val h = BackupHarness()
    private val main = { c: String -> AppKey(c, MAIN) }

    /** T-BACKUP-01 state: 5 favourites, 2 hidden, 2 renames, 1 icon override, 1 widget, 3 user themes. */
    private fun seed() {
        val private = AppKey("com.secret.diary/.Main", PRIVATE)
        h.apps.data =
            AppData(
                overrides =
                    listOf(
                        AppOverride(main("com.a/.A"), hidden = true),
                        AppOverride(main("com.b/.B"), hidden = true),
                        AppOverride(main("com.c/.C"), customLabel = "Cee"),
                        AppOverride(main("com.d/.D"), customLabel = "Dee", icon = IconOverride("com.pack", "d")),
                        AppOverride(AppKey("com.work.mail/.Mail", WORK), hidden = true),
                        // Never written by the repositories (P-5); here to prove the export would drop it anyway.
                        AppOverride(private, customLabel = "Secret"),
                    ),
                favorites = listOf("com.c/.C", "com.d/.D", "com.e/.E", "com.f/.F", "com.g/.G").map(main) + private,
                widgets =
                    listOf(
                        WidgetPlacement(
                            42,
                            WidgetProviderRef("com.clock/.Clock", MAIN),
                            heightStep = 3,
                            replaceClock = true,
                        ),
                    ),
            )
        listOf(BackupFixtures.THEME_A, BackupFixtures.THEME_B, "2d3e4f50-6172-4c83-9d94-b5c6d7e8f9a0").forEachIndexed {
            i,
            id,
            ->
            h.themes.stored[id] = StoredThemeFile(id, themeTree(id, "User $i"))
        }
        h.themes.activeId = BackupFixtures.THEME_B
        h.settings.set(h.settings.value.copy(listTwoColumn = false, launchLogPaused = true))
        h.install("com.a/.A", "com.b/.B", "com.c/.C", "com.d/.D", "com.e/.E", "com.f/.F", "com.g/.G")
        h.install("com.work.mail/.Mail", serial = WORK, kind = ProfileKind.WORK)
        h.profiles.profiles.value = h.profiles.profiles.value + Profile(WORK, ProfileKind.WORK, false, true)
    }

    private fun themeTree(
        id: String,
        name: String,
    ): JsonObject = (JsonParser.parse(BackupFixtures.theme(id, name).toByteArray()) as Outcome.Ok).value as JsonObject

    private fun <T> Outcome<T, BackupError>.ok(): T = (this as Outcome.Ok).value

    @Test
    fun tBackup01_defaultExport_hasNoPrivateWorkOrLogData() =
        runBlocking<Unit> {
            seed()
            h.launchLog.record(main("com.a/.A"), ProfileKind.MAIN)
            val s = h.service()
            assertThat(s.suggestedFileName()).matches("strata-\\d{4}-\\d{2}-\\d{2}\\.strata-backup\\.json")
            val report = s.export("content://doc/1", ExportOptions()).ok()
            val text = String(h.documents.documents.getValue("content://doc/1"))
            val root = (JsonParser.parse(text.toByteArray(), BackupFormat.LIMITS) as Outcome.Ok).value as JsonObject
            assertThat(root.keys).doesNotContain("launchLog")
            assertThat(text).doesNotContain("com.secret.diary")
            assertThat(text).doesNotContain("Secret")
            assertThat(text).doesNotContain("\"work\"")
            assertThat(text).doesNotContain("install_id")
            assertThat(text).doesNotContain(h.settings.value.installId)
            assertThat(report.counts.favorites).isEqualTo(5)
            assertThat(report.counts.hidden).isEqualTo(2)
            assertThat(report.counts.renames).isEqualTo(2)
            assertThat(report.counts.iconOverrides).isEqualTo(1)
            assertThat(report.counts.widgets).isEqualTo(1)
            assertThat(report.counts.themes).isEqualTo(3)
            assertThat(BackupCodec.decode(text.toByteArray())).isInstanceOf(Outcome.Ok::class.java)
            // Widget IDs never leave the device.
            assertThat(text).doesNotContain("appWidgetId")
        }

    @Test
    fun tBackup02_optIns_addWorkEntriesAndDayBuckets() =
        runBlocking<Unit> {
            seed()
            h.launchLog.record(main("com.a/.A"), ProfileKind.MAIN)
            h.launchLog.record(main("com.a/.A"), ProfileKind.MAIN)
            h.launchLog.record(AppKey("com.secret.diary/.Main", PRIVATE), ProfileKind.MAIN) // a serial that is private
            h.service().export("content://doc/2", ExportOptions(workProfile = true, launchLog = true)).ok()
            val root =
                (JsonParser.parse(h.documents.documents.getValue("content://doc/2"), BackupFormat.LIMITS) as Outcome.Ok)
                    .value as JsonObject
            val apps = root["apps"] as JsonArray
            assertThat(apps.map { ((it as JsonObject)["profile"] as JsonString).value }).contains("work")
            val log = root["launchLog"] as JsonArray
            assertThat(log).hasSize(1)
            val day = log.single() as JsonObject
            assertThat(day.keys).containsExactly("component", "profile", "day", "count")
            assertThat((day["day"] as JsonString).value).matches("\\d{4}-\\d{2}-\\d{2}")
            assertThat(String(h.documents.documents.getValue("content://doc/2"))).doesNotContain("com.secret.diary")
        }

    @Test
    fun export_failsClosed_withoutProfilesAndOnWriteFailure() =
        runBlocking<Unit> {
            h.profiles.profiles.value = emptyList()
            assertThat(h.service().export("content://x", ExportOptions())).isEqualTo(Outcome.Err(BackupError.NotReady))
            h.profiles.profiles.value = listOf(Profile(MAIN, ProfileKind.MAIN, false, true))
            h.documents.failing += "content://x"
            assertThat(
                h.service().export("content://x", ExportOptions()),
            ).isEqualTo(Outcome.Err(BackupError.WriteFailed))
        }

    @Test
    fun tBackup04_restoreThenUndo_canonicalDumpsMatch() =
        runBlocking<Unit> {
            // S1 → backup B.
            seed()
            val s = h.service()
            s.export("content://b", ExportOptions(workProfile = true)).ok()
            val s1 = h.dump()
            // S2: different everything.
            h.apps.data =
                AppData(
                    overrides = listOf(AppOverride(main("com.z/.Z"), customLabel = "Zed", missingSince = 5L)),
                    favorites = listOf(main("com.z/.Z")),
                    widgets = listOf(WidgetPlacement(7, WidgetProviderRef("com.w/.W", MAIN))),
                )
            h.settings.set(h.settings.value.copy(listTwoColumn = true, hapticsEnabled = false))
            h.themes.stored.remove(BackupFixtures.THEME_A)
            h.themes.stored["3e4f5061-7283-4d94-8ea5-c6d7e8f9a0b1"] =
                StoredThemeFile(
                    "3e4f5061-7283-4d94-8ea5-c6d7e8f9a0b1",
                    themeTree("3e4f5061-7283-4d94-8ea5-c6d7e8f9a0b1", "S2"),
                )
            h.themes.activeId = BackupHarness.BUILTIN
            h.themes.hiddenBuiltins = setOf(BackupHarness.BUILTIN)
            val s2 = h.dump()

            val preview = s.preview("content://b").ok()
            assertThat(preview.counts.favorites).isEqualTo(5)
            assertThat(h.dump()).isEqualTo(s2) // nothing written by the preview
            s.restore(preview.token).ok()
            val after = h.dump()
            // Equal to S1, except that the widget is a placeholder (its ID cannot travel) and the S2-only theme stays.
            assertThat(after.settings).isEqualTo(s1.settings)
            assertThat(
                after.apps.overrides.toSet(),
            ).isEqualTo(
                s1.apps.overrides
                    .filter { it.key.userSerial != PRIVATE }
                    .toSet(),
            )
            assertThat(after.apps.favorites).isEqualTo(s1.apps.favorites.filter { it.userSerial != PRIVATE })
            assertThat(
                after.apps.widgets
                    .single()
                    .state,
            ).isEqualTo(WidgetPlacementState.NEEDS_REBIND)
            assertThat(after.themes.activeId).isEqualTo(s1.themes.activeId)
            assertThat(after.themes.themes.map { it.id }).containsAtLeastElementsIn(s1.themes.themes.map { it.id })
            assertThat(after.themes.hiddenBuiltins).isEmpty()
            assertThat(s.undoAvailable.value).isTrue()

            assertThat(s.undoRestore()).isEqualTo(Outcome.Ok(Unit))
            assertThat(h.dump()).isEqualTo(s2)
            assertThat(s.undoAvailable.value).isFalse()
            assertThat(h.files.files).isEmpty()
            assertThat(s.undoRestore()).isEqualTo(Outcome.Err(BackupError.NothingToUndo))
        }

    @Test
    fun undo_isGoneAfterTheNextStart() =
        runBlocking<Unit> {
            seed()
            h.service().export("content://b", ExportOptions()).ok()
            val s = h.service()
            s.restore(s.preview("content://b").ok().token).ok()
            assertThat(h.files.files.keys).containsExactly(DefaultBackupService.UNDO)
            val next = h.service()
            next.onProcessStart()
            assertThat(h.files.files).isEmpty()
            assertThat(next.undoRestore()).isEqualTo(Outcome.Err(BackupError.NothingToUndo))
        }

    @Test
    fun tBackup05_06_rejectedFiles_changeNothing() =
        runBlocking<Unit> {
            seed()
            val before = h.dump()
            val s = h.service()
            val cases =
                mapOf(
                    BackupFixtures.newerMajor() to BackupError.NewerVersion,
                    BackupFixtures.themeFile() to BackupError.NotABackup,
                    BackupFixtures.oversize() to BackupError.TooLarge,
                    BackupFixtures.invalidUtf8() to BackupError.Damaged(0, 0),
                )
            for ((bytes, error) in cases) {
                h.documents.documents["content://bad"] = bytes
                assertThat(s.preview("content://bad")).isEqualTo(Outcome.Err(error))
            }
            h.documents.documents["content://bad"] = BackupFixtures.truncated()
            assertThat((s.preview("content://bad") as Outcome.Err).error).isInstanceOf(BackupError.Damaged::class.java)
            assertThat(s.preview("file:///sdcard/x.json")).isEqualTo(Outcome.Err(BackupError.Unreadable))
            assertThat(h.dump()).isEqualTo(before)
            assertThat(h.files.files).isEmpty()
        }

    @Test
    fun tBackup07_newerMinor_previewCountsUnknownsAndRestoresKnown() =
        runBlocking<Unit> {
            h.documents.documents["content://m"] = BackupFixtures.newerMinor()
            val s = h.service()
            val p = s.preview("content://m").ok()
            assertThat(p.newerSettingsIgnored).isEqualTo(3)
            s.restore(p.token).ok()
            assertThat(h.settings.value.hapticsEnabled).isFalse()
            assertThat(h.settings.value.listTwoColumn).isFalse()
        }

    @Test
    fun tBackup08_missingApps_listedAndKeptPending() =
        runBlocking<Unit> {
            h.install("com.example.maps/.MainActivity", "com.example.mail/.Inbox")
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://v").ok()
            assertThat(p.missingApps).containsExactly("com.example.missing")
            s.restore(p.token).ok()
            val rows =
                h.apps.data.overrides
                    .associateBy { it.key.component }
            assertThat(rows.getValue("com.example.missing/.Main").missingSince).isEqualTo(h.clock.now)
            assertThat(rows.getValue("com.example.missing/.Main").customLabel).isEqualTo("Gone")
            assertThat(rows.getValue("com.example.maps/.MainActivity").missingSince).isNull()
            assertThat(h.apps.data.favorites).contains(AppKey("com.example.missing/.Main", MAIN))
            assertThat(h.catalog.reconcileRequests).isAtLeast(1)
        }

    @Test
    fun tBackup09_validThemeRestored_invalidListed() =
        runBlocking<Unit> {
            h.documents.documents["content://t"] = BackupFixtures.mixedThemes()
            val s = h.service()
            val p = s.preview("content://t").ok()
            assertThat(p.excludedThemes).containsExactly("Broken")
            assertThat(s.restore(p.token)).isInstanceOf(Outcome.Ok::class.java)
            assertThat(h.themes.stored.keys).containsExactly(BackupFixtures.THEME_A)
            assertThat(h.themes.activeId).isEqualTo(BackupFixtures.THEME_A)
        }

    @Test
    fun newerTheme_restoredAsNeedsUpdate() =
        runBlocking<Unit> {
            h.documents.documents["content://n"] = BackupFixtures.newerTheme()
            val s = h.service()
            val p = s.preview("content://n").ok()
            assertThat(p.needsUpdateThemes).containsExactly("From the future")
            s.restore(p.token).ok()
            assertThat(
                h.themes.stored
                    .getValue(BackupFixtures.THEME_B)
                    .needsAppUpdate,
            ).isTrue()
        }

    @Test
    fun tWidget10_restoredWidget_isAReAddPlaceholder() =
        runBlocking<Unit> {
            h.documents.documents["content://w"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://w").ok()
            assertThat(p.widgetsToReAdd).isEqualTo(1)
            s.restore(p.token).ok()
            val w =
                h.apps.data.widgets
                    .single()
            assertThat(w.state).isEqualTo(WidgetPlacementState.NEEDS_REBIND)
            assertThat(w.appWidgetId).isLessThan(0)
            assertThat(w.provider).isEqualTo(WidgetProviderRef("com.example.clock/.ClockWidget", MAIN))
            assertThat(w.replaceClock).isTrue()
        }

    @Test
    fun workEntries_withoutWorkProfile_areSkippedAndCounted() =
        runBlocking<Unit> {
            h.documents.documents["content://w"] =
                BackupFixtures
                    .valid(
                        work = true,
                        workEntries = """, { "component": "com.work/.W", "profile": "work", "hidden": true }""",
                    ).toByteArray()
            val s = h.service()
            val p = s.preview("content://w").ok()
            assertThat(p.workSkipped).isEqualTo(1)
            s.restore(p.token).ok()
            assertThat(
                h.apps.data.overrides
                    .map { it.key.component },
            ).doesNotContain("com.work/.W")
        }

    @Test
    fun failedStep_rollsBackToTheSnapshot() =
        runBlocking<Unit> {
            seed()
            val before = h.dump()
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://v").ok()
            h.themes.failNextRestore = true
            assertThat(s.restore(p.token)).isEqualTo(Outcome.Err(BackupError.RestoreFailed))
            assertThat(h.dump()).isEqualTo(before.withoutPrivate())
            assertThat(h.files.files).isEmpty()
            assertThat(s.undoAvailable.value).isFalse()
            assertThat(s.restore(p.token)).isEqualTo(Outcome.Err(BackupError.Expired))
        }

    /** Rows of the private serial are never kept by a snapshot (fail closed), so the comparison leaves them out. */
    private fun BackupHarness.Dump.withoutPrivate() =
        copy(
            apps =
                AppData(
                    apps.overrides.filter { it.key.userSerial != PRIVATE },
                    apps.favorites.filter { it.userSerial != PRIVATE },
                    apps.widgets.filter { it.provider.userSerial != PRIVATE },
                ),
        )

    /** Review fix 1: the rollback's journal write fails; the old state is still written and no journal replays the restore. */
    @Test
    fun rollback_whenTheJournalWriteFails_stillWritesTheOldState() =
        runBlocking<Unit> {
            seed()
            val before = h.dump().withoutPrivate()
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://v").ok()
            h.themes.failNextRestore = true
            // Write 0 of the journal is the restore's commit point; write 1 is the rollback's.
            h.files.failWrite = { name, n -> name == DefaultBackupService.JOURNAL && n == 1 }
            assertThat(s.restore(p.token)).isEqualTo(Outcome.Err(BackupError.RestoreFailed))
            assertThat(h.dump()).isEqualTo(before)
            assertThat(h.files.files).isEmpty()
            // The next start has nothing to replay: still the old state.
            h.service().onProcessStart()
            assertThat(h.dump()).isEqualTo(before)
        }

    /** Both the step and the rollback fail: an honest error, the journal = the snapshot, Undo can finish it. */
    @Test
    fun rollback_thatFailsToo_isReportedAndRecoverable() =
        runBlocking<Unit> {
            seed()
            val before = h.dump().withoutPrivate()
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://v").ok()
            h.themes.failRestores = 2
            assertThat(s.restore(p.token)).isEqualTo(Outcome.Err(BackupError.RestoreIncomplete))
            assertThat(s.undoAvailable.value).isTrue()
            assertThat(RestoreStateCodec.decode(h.files.files.getValue(DefaultBackupService.JOURNAL))!!.apps)
                .isEqualTo(before.apps)
            assertThat(s.undoRestore()).isEqualTo(Outcome.Ok(Unit))
            assertThat(h.dump()).isEqualTo(before)
            assertThat(h.files.files).isEmpty()
        }

    /** Review fix 2: no private-space serial or component ever reaches `restore/` (undo snapshot, journal). */
    @Test
    fun restoreFiles_neverHoldPrivateRows() =
        runBlocking<Unit> {
            seed()
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://v").ok()
            val seen = mutableListOf<String>()
            h.files.failWrite = { _, _ -> false }
            // Capture every file while it exists: after the journal write and at the end.
            h.themes.beforeWrite = {
                h.files.files.values
                    .forEach { seen += String(it) }
            }
            s.restore(p.token).ok()
            h.files.files.values
                .forEach { seen += String(it) }
            assertThat(seen).isNotEmpty()
            for (text in seen) {
                assertThat(text).doesNotContain("com.secret.diary")
                assertThat(text).doesNotContain("Secret")
                assertThat(text).doesNotContain("\"userSerial\":$PRIVATE")
            }
        }

    /** Review fix 3: an exact undo keeps a stored theme the snapshot could not carry (unreadable file). */
    @Test
    fun undo_keepsThemesTheSnapshotCouldNotRead() =
        runBlocking<Unit> {
            val broken = "4f506172-8394-4ea5-9fb6-d7e8f9a0b1c2"
            h.themes.stored[broken] = StoredThemeFile(broken, themeTree(broken, "Broken on disk"))
            h.themes.unreadable += broken
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            s.restore(s.preview("content://v").ok().token).ok()
            assertThat(h.themes.stored.keys).containsAtLeast(broken, BackupFixtures.THEME_A)
            s.undoRestore()
            assertThat(h.themes.stored.keys).containsExactly(broken)
        }

    /** Review fix 5: entries of a profile that is not loaded (paused work profile) are not stamped missing. */
    @Test
    fun pausedWorkProfile_entriesAreNotMarkedMissing() =
        runBlocking<Unit> {
            h.profiles.profiles.value =
                h.profiles.profiles.value + Profile(WORK, ProfileKind.WORK, quietMode = true, available = false)
            h.install("com.example.maps/.MainActivity")
            h.documents.documents["content://w"] =
                BackupFixtures
                    .valid(
                        work = true,
                        workEntries = """, { "component": "com.work/.W", "profile": "work", "hidden": true }""",
                    ).toByteArray()
            val s = h.service()
            val p = s.preview("content://w").ok()
            assertThat(p.missingApps).doesNotContain("com.work")
            s.restore(p.token).ok()
            val work =
                h.apps.data.overrides
                    .single { it.key.userSerial == WORK }
            assertThat(work.hidden).isTrue()
            assertThat(work.missingSince).isNull()
            // The main profile is loaded: its missing app is still pending.
            assertThat(
                h.apps.data.overrides
                    .single { it.key.component == "com.example.missing/.Main" }
                    .missingSince,
            ).isEqualTo(h.clock.now)
        }

    /** Review fix 6: a damaged or oversize journal is discarded, but the undo snapshot stays and Undo is offered. */
    @Test
    fun damagedJournal_keepsTheUndoSnapshot() =
        runBlocking<Unit> {
            for (journal in listOf("{ damaged".toByteArray(), ByteArray(RestoreStateCodec.MAX_BYTES + 1))) {
                seed()
                val before = h.dump().withoutPrivate()
                h.documents.documents["content://v"] = BackupFixtures.validBytes()
                val s = h.service()
                s.restore(s.preview("content://v").ok().token).ok()
                h.files.files[DefaultBackupService.JOURNAL] = journal
                val next = h.service()
                assertThat(next.onProcessStart().journalReplayed).isFalse()
                assertThat(h.files.files.keys).containsExactly(DefaultBackupService.UNDO)
                assertThat(next.undoAvailable.value).isTrue()
                assertThat(next.undoRestore()).isEqualTo(Outcome.Ok(Unit))
                assertThat(h.dump()).isEqualTo(before)
            }
        }

    @Test
    fun snapshotWriteFailure_restoresNothing() =
        runBlocking<Unit> {
            seed()
            val before = h.dump()
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val p = s.preview("content://v").ok()
            h.files.failWrites = true
            assertThat(s.restore(p.token)).isEqualTo(Outcome.Err(BackupError.RestoreFailed))
            assertThat(h.dump()).isEqualTo(before)
        }

    @Test
    fun tBackup11_restoreDetection_runsPostRestoreStepsOnce() =
        runBlocking<Unit> {
            // Auto Backup brought settings (with install_id) but not the marker (no_backup is never backed up).
            val id = h.settings.value.installId
            val r1 = h.service().onProcessStart()
            assertThat(r1.restoreDetected).isTrue()
            assertThat(h.themes.revalidations).isEqualTo(1)
            assertThat(h.catalog.reconcileRequests).isEqualTo(1)
            assertThat(h.settings.value.installId).isNotEqualTo(id)
            assertThat(h.marker.value).isEqualTo(h.settings.value.installId)
            // The next start is an ordinary one.
            val r2 = h.service().onProcessStart()
            assertThat(r2.restoreDetected).isFalse()
            assertThat(h.themes.revalidations).isEqualTo(1)
            // A different marker (data moved from another install) is detected again.
            h.marker.value = "11111111-2222-4333-8444-555555555555"
            assertThat(h.service().onProcessStart().restoreDetected).isTrue()
        }

    @Test
    fun preview_tokensExpireAndCanBeDiscarded() =
        runBlocking<Unit> {
            h.documents.documents["content://v"] = BackupFixtures.validBytes()
            val s = h.service()
            val first: RestorePreview = s.preview("content://v").ok()
            s.discard(first.token)
            assertThat(s.restore(first.token)).isEqualTo(Outcome.Err(BackupError.Expired))
            val a = s.preview("content://v").ok()
            s.preview("content://v").ok()
            s.preview("content://v").ok()
            assertThat(s.restore(a.token)).isEqualTo(Outcome.Err(BackupError.Expired))
        }
}
