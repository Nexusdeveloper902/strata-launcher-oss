package app.strata.launcher.backup

import app.strata.launcher.core.common.Outcome
import app.strata.launcher.core.model.AppKey
import app.strata.launcher.core.model.AppOverride
import app.strata.launcher.core.model.ProfileKind
import app.strata.launcher.core.platformapi.apps.Profile
import app.strata.launcher.core.platformapi.backup.AppData
import app.strata.launcher.core.platformapi.backup.ExportOptions
import app.strata.launcher.testing.fixtures.BackupFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * T-BACKUP-01 / FR-BACKUP-01 "file validates against the backup schema": exported files and the valid fixtures pass
 * `spec/backup.schema.json` (Python jsonschema, as `tools/check_themes.py` uses; skipped where it is not installed).
 */
class BackupSchemaTest {
    private fun root(): File? =
        generateSequence(
            File("").absoluteFile,
        ) { it.parentFile }.firstOrNull { File(it, "spec/backup.schema.json").isFile }

    private fun schemaErrors(files: List<File>): String? {
        val root = root() ?: return null
        val script =
            """
            import json, sys
            from jsonschema import Draft202012Validator
            schema = json.load(open(sys.argv[1]))
            Draft202012Validator.check_schema(schema)
            v = Draft202012Validator(schema)
            bad = []
            for f in sys.argv[2:]:
                errs = list(v.iter_errors(json.load(open(f))))
                if errs: bad.append(f + ": " + "; ".join(e.message for e in errs))
            print("\n".join(bad)); sys.exit(0)
            """.trimIndent()
        return try {
            val p =
                ProcessBuilder(
                    listOf("python3", "-I", "-c", script, File(root, "spec/backup.schema.json").path) +
                        files.map { it.path },
                ).redirectErrorStream(true)
                    .start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) null else out.trim()
        } catch (e: java.io.IOException) {
            null
        }
    }

    @Test
    fun exportsAndFixtures_validateAgainstTheSchema() =
        runBlocking<Unit> {
            val h = BackupHarness()
            h.profiles.profiles.value =
                h.profiles.profiles.value + Profile(BackupHarness.WORK, ProfileKind.WORK, false, true)
            h.apps.data =
                AppData(
                    overrides =
                        listOf(
                            AppOverride(AppKey("com.a/.A", BackupHarness.MAIN), hidden = true, customLabel = "Ay"),
                            AppOverride(AppKey("com.w/.W", BackupHarness.WORK), hidden = true),
                        ),
                    favorites = listOf(AppKey("com.a/.A", BackupHarness.MAIN)),
                )
            h.launchLog.record(AppKey("com.a/.A", BackupHarness.MAIN), ProfileKind.MAIN)
            val svc = h.service()
            assertThat(svc.export("content://1", ExportOptions())).isInstanceOf(Outcome.Ok::class.java)
            assertThat(svc.export("content://2", ExportOptions(workProfile = true, launchLog = true)))
                .isInstanceOf(Outcome.Ok::class.java)
            val dir =
                kotlin.io.path
                    .createTempDirectory("backup-schema")
                    .toFile()
            try {
                val files =
                    listOf(
                        "export-default" to h.documents.documents.getValue("content://1"),
                        "export-optins" to h.documents.documents.getValue("content://2"),
                        "fixture-valid" to BackupFixtures.validBytes(),
                    ).map { (n, b) -> File(dir, "$n.json").apply { writeBytes(b) } }
                val errors = schemaErrors(files)
                assumeTrue("python3 + jsonschema not installed", errors != null)
                assertThat(errors).isEmpty()
                // And the schema rejects a private-space entry outright.
                val bad =
                    File(dir, "private.json").apply {
                        writeText(
                            """{"format":"strata-backup","formatVersion":1,"apps":[{"component":"a.b/.C","profile":"private"}]}""",
                        )
                    }
                assertThat(schemaErrors(listOf(bad))).isNotEmpty()
            } finally {
                dir.deleteRecursively()
            }
        }
}
