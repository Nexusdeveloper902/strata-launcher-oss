package app.strata.launcher.core.data.onboarding

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `onboarding_done` flag storage used by D-008 entry routing (T-HOME-13, FR-ONBOARD-01). */
class DataStoreOnboardingStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun <T> withStore(file: File, block: suspend (DataStoreOnboardingStore) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            return runBlocking { block(DataStoreOnboardingStore.create(scope) { file }) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun defaultsToFalse() {
        val file = File(tmp.root, DataStoreOnboardingStore.FILE_NAME)
        assertThat(withStore(file) { it.onboardingDone.first() }).isFalse()
    }

    @Test
    fun persistsAcrossInstances() {
        val file = File(tmp.root, DataStoreOnboardingStore.FILE_NAME)
        withStore(file) { it.setOnboardingDone(true) }
        assertThat(withStore(file) { it.onboardingDone.first() }).isTrue()
    }

    @Test
    fun corruptFileFallsBackToDefault() {
        val file = File(tmp.root, DataStoreOnboardingStore.FILE_NAME)
        file.writeText("not a flag file")
        assertThat(withStore(file) { it.onboardingDone.first() }).isFalse()
    }
}
