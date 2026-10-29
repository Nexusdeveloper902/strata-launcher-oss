package app.strata.launcher.core.data.onboarding

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.strata.launcher.core.platformapi.settings.OnboardingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/** [OnboardingStore] backed by a DataStore file under `filesDir/datastore/` (included in Auto Backup). */
class DataStoreOnboardingStore internal constructor(
    private val dataStore: DataStore<EntryFlags>,
) : OnboardingStore {
    override val onboardingDone: Flow<Boolean> =
        dataStore.data.map { it.onboardingDone }.distinctUntilChanged()

    override suspend fun setOnboardingDone(done: Boolean) {
        dataStore.updateData { it.copy(onboardingDone = done) }
    }

    companion object {
        /** File name inside `filesDir/datastore/`. */
        const val FILE_NAME = "entry_flags.bin"

        /** Production instance at `filesDir/datastore/entry_flags.bin`; the file is resolved lazily on [scope]. */
        fun create(context: Context, scope: CoroutineScope): DataStoreOnboardingStore {
            val appContext = context.applicationContext
            return create(scope) { appContext.dataStoreFile(FILE_NAME) }
        }

        /** [produceFile] is called lazily on [scope], never on the main thread. */
        fun create(scope: CoroutineScope, produceFile: () -> File): DataStoreOnboardingStore =
            DataStoreOnboardingStore(
                DataStoreFactory.create(
                    serializer = EntryFlagsSerializer,
                    corruptionHandler = ReplaceFileCorruptionHandler { EntryFlags() },
                    scope = scope,
                    produceFile = produceFile,
                ),
            )
    }
}
