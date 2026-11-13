package app.strata.launcher.settings

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.strata.launcher.DeviceStateRule
import app.strata.launcher.appGraph
import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.common.log.LogRedaction
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.feature.home.HomeTestTags
import app.strata.launcher.feature.search.SearchTestTags
import app.strata.launcher.focusReport
import app.strata.launcher.shell
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T-PRIV-06 (I): flows that see labels, a query and a component that does not exist, then About -> "Save diagnostic
 * log" through the production service. The export holds none of them: no query, no custom or system label, no
 * private-space component, and no package name of another app in plain text (only `<id:xxxxxx>` hashes). This runs on
 * the debug build, whose own log file may hold package names (ARCHITECTURE §11); the export is redacted either way.
 * The system "save as" screen cannot be driven on GMD, so the document is our own FileProvider URI.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticLogDeviceTest {
    @get:Rule
    val deviceState = DeviceStateRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val graph get() = context.appGraph
    private val device = UiDevice.getInstance(instrumentation)
    private val dir get() = File(context.cacheDir, "share/diagnostic-test")
    private var selfKey: AppKey? = null
    private var originalOverride: AppOverride? = null

    /** The app's own log files before the test, put back afterwards (the test's entries must not stay in it). */
    private val logDir get() = File(context.noBackupFilesDir, "logs")
    private var savedLogs: Map<String, ByteArray> = emptyMap()

    @Before
    fun before() =
        runBlocking {
            graph.startCatalog()
            val main =
                withTimeout(10_000) {
                    graph.profiles.profiles.first { l ->
                        l.any { it.kind == ProfileKind.MAIN }
                    }
                }
            val key =
                AppKey(
                    "${context.packageName}/${SettingsActivity::class.java.name}",
                    main
                        .first {
                            it.kind ==
                                ProfileKind.MAIN
                        }.serial,
                )
            selfKey = key
            originalOverride = graph.overrides.overrides.first()[key]
            graph.logger.flush(FLUSH_MS)
            savedLogs = logDir.listFiles()?.associate { it.name to it.readBytes() } ?: emptyMap()
        }

    @After
    fun after() =
        runBlocking {
            val key = selfKey ?: return@runBlocking
            graph.overrides.setCustomLabel(key, ProfileKind.MAIN, originalOverride?.customLabel)
            dir.deleteRecursively()
            graph.logger.flush(FLUSH_MS)
            logDir.listFiles()?.forEach { it.delete() }
            logDir.mkdirs()
            for ((name, bytes) in savedLogs) File(logDir, name).writeBytes(bytes)
        }

    @Test
    fun tPriv06_exportHasNoLabelsQueriesOrPackages() =
        runBlocking {
            val key = checkNotNull(selfKey)
            // Labels that the log text already holds before any flow (a tag such as "Settings") cannot be told from a
            // leak, so they are left out of the label check below.
            val baseline = checkNotNull(graph.diagnostics.build()) { "diagnostic log not built" }
            graph.settingsRepository.update { it.copy(onboardingDone = true) }
            graph.overrides.setCustomLabel(key, ProfileKind.MAIN, CUSTOM_LABEL)
            shell(
                "am start -n ${context.packageName}/.home.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME",
            )
            assertWithMessage("home not shown. ${focusReport()}")
                .that(device.wait(Until.hasObject(By.res(HomeTestTags.ROOT)), 15_000))
                .isTrue()
            shell("input text $QUERY")
            device.wait(Until.hasObject(By.res(SearchTestTags.LAYER)), 10_000)
            device.pressBack()
            device.pressBack()
            // A launch of a component that does not exist: the platform's exception names it (and the user).
            // API 26 answers with a NullPointerException from system_server; every API must give a typed failure.
            var launched: Any? = null
            instrumentation.runOnMainSync { launched = graph.appLauncher.launch(AppKey(GONE, key.userSerial), null) }
            assertWithMessage("launch of a missing component").that(launched).isInstanceOf(Outcome.Err::class.java)
            // A platform-style message as adapters receive them.
            graph.logger.w(
                "DeviceTest",
                "start of $GONE failed",
                SecurityException("Permission Denial: $GONE uid 10123"),
            )

            val text = checkNotNull(graph.diagnostics.build()) { "diagnostic log not built" }
            val entries =
                withTimeout(15_000) {
                    graph.appCatalog.catalog.first { c ->
                        c.entries.any {
                            it.packageName !=
                                context.packageName
                        }
                    }
                }.entries
            val foreignPackages =
                entries
                    .map { it.packageName }
                    .filter { p -> p != context.packageName && !p.startsWith("android.") && p.contains('.') }
                    .distinct()
            val labels =
                entries
                    .filter { it.packageName != context.packageName }
                    .map { it.systemLabel.trim() }
                    .filter { it.length >= 3 && !baseline.contains(it) }
                    .distinct()
            val privateComponents = entries.filter { it.profileKind == ProfileKind.PRIVATE }.map { it.key.component }
            // The checks below need something to look for (GMD images and phones have other launchable apps).
            assertWithMessage("other apps' packages to look for").that(foreignPackages).isNotEmpty()
            assertWithMessage("other apps' labels to look for").that(labels).isNotEmpty()
            assertThat(text).startsWith("Strata Launcher diagnostic log\n")
            assertThat(text).doesNotContain(QUERY)
            assertThat(text).doesNotContain(CUSTOM_LABEL)
            assertThat(text).doesNotContain("com.example.gone")
            assertThat(text).doesNotContain("Permission Denial")
            assertWithMessage(
                "package names in plain text",
            ).that(foreignPackages.filter { text.contains(it) }).isEmpty()
            assertWithMessage("app labels").that(labels.filter { text.contains(it) }).isEmpty()
            assertWithMessage("private components").that(privateComponents.filter { text.contains(it) }).isEmpty()
            assertThat(text).contains("<id:${LogRedaction.hash(GONE)}>")

            // Saving writes exactly the previewed text.
            dir.mkdirs()
            val f = File(dir, "log.txt").apply { writeBytes(ByteArray(0)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f).toString()
            assertThat(graph.diagnostics.save(uri, text)).isTrue()
            assertThat(f.readText()).isEqualTo(text)
        }

    private companion object {
        const val FLUSH_MS = 2_000L
        const val QUERY = "zz-diag-secret"
        const val CUSTOM_LABEL = "Diag Secret Rename"
        const val GONE = "com.example.gone/com.example.gone.Main"
    }
}
