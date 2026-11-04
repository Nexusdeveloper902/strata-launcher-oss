package app.strata.launcher.core.data.theme

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.json.JsonLimits
import app.strata.launcher.core.theme.contrast.ContrastStatus
import app.strata.launcher.core.theme.store.ThemeIndex
import app.strata.launcher.core.theme.store.ThemeIndexEntry
import app.strata.launcher.core.theme.store.ThemeOrigin
import app.strata.launcher.testing.fakes.FakeClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** The Android halves of the theme stores (THEME_SYSTEM §11): AtomicFile files, the Proto index, boot attempts. */
@RunWith(AndroidJUnit4::class)
class ThemeStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "themes")
    private val store by lazy { AtomicFileThemeFileStore({ root }) }

    @Test
    fun files_writeReadListMoveDelete() {
        assertThat(store.read("user/a.strata-theme.json")).isNull()
        store.write("user/a.strata-theme.json", "A".toByteArray())
        store.write("user/b.strata-theme.json", "B".toByteArray())
        store.write("user/a.strata-theme.json", "A2".toByteArray())
        assertThat(store.read("user/a.strata-theme.json")!!.decodeToString()).isEqualTo("A2")
        // AtomicFile's temp and legacy backup files are not entries.
        File(root, "user/c.strata-theme.json.new").writeText("partial")
        File(root, "user/d.strata-theme.json.bak").writeText("legacy")
        assertThat(store.list("user")).containsExactly("a.strata-theme.json", "b.strata-theme.json").inOrder()
        store.move("user/b.strata-theme.json", "quarantine/b.strata-theme.json")
        assertThat(store.list("quarantine")).containsExactly("b.strata-theme.json")
        assertThat(store.delete("user/a.strata-theme.json")).isTrue()
        assertThat(store.list("user")).isEmpty()
        assertThat(store.list("missing")).isEmpty()
    }

    @Test
    fun files_readIsCappedAtLimitPlusOne() {
        store.write("user/big.strata-theme.json", ByteArray(JsonLimits.THEME.maxBytes + 4096))
        assertThat(store.read("user/big.strata-theme.json")!!.size).isEqualTo(JsonLimits.THEME.maxBytes + 1)
    }

    @Test
    fun files_namesCannotEscapeTheRoot() {
        for (bad in listOf("../x", "/etc/x", "user/../../x", "user//x", "")) {
            assertThrows(IllegalArgumentException::class.java) { store.read(bad) }
        }
    }

    private fun <T> withIndex(block: (DataStoreThemeIndexStore) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            return block(DataStoreThemeIndexStore.create(scope) { File(tmp.root, "theme_index.pb") })
        } finally {
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        }
    }

    @Test
    fun index_roundTripsEveryField_missingAndCorruptReadAsNull() {
        assertThat(withIndex { it.read() }).isNull()
        val index =
            ThemeIndex(
                entries =
                    listOf(
                        ThemeIndexEntry("a", "Alpha", ThemeOrigin.USER, "ff", 1, 2, 3, ContrastStatus.LOW_CONTRAST),
                        ThemeIndexEntry("b", "Beta", ThemeOrigin.IMPORTED, needsAppUpdate = true, causedProblem = true),
                        ThemeIndexEntry(
                            "c",
                            "Gamma",
                            ThemeOrigin.BUILTIN,
                            contrastStatus = ContrastStatus.AA_ANY_WALLPAPER,
                        ),
                    ),
                activeThemeId = "a",
                lastKnownGoodId = "c",
                applyInProgress = true,
                applyStartedAt = 42,
                applyCrashCount = 1,
            )
        withIndex { it.write(index) }
        assertThat(withIndex { it.read() }).isEqualTo(index)
        File(tmp.root, "theme_index.pb").writeBytes(byteArrayOf(0x0A, 0x7F, 0x01))
        assertThat(withIndex { it.read() }).isNull()
    }

    @Test
    fun bootAttempts_windowAndReset() {
        val clock = FakeClock(1_000_000)
        val file = File(tmp.root, "boot.state")
        val attempts = BootAttempts({ file }, clock)
        assertThat(attempts.onStart()).isFalse() // 1st start
        assertThat(attempts.onStart()).isFalse() // 1 failed
        assertThat(attempts.onStart()).isTrue() // 2 failed within 5 min
        attempts.onSucceeded()
        assertThat(file.exists()).isFalse()
        assertThat(attempts.onStart()).isFalse()
        clock.advanceBy(BootAttempts.WINDOW_MS + 1)
        assertThat(attempts.onStart()).isFalse() // old attempts expired
        file.writeText("garbage")
        assertThat(attempts.onStart()).isFalse()
    }
}
