package app.strata.launcher.core.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import app.strata.launcher.core.data.settings.Settings as ProtoSettings

/**
 * [SettingsRepository] over Proto DataStore `filesDir/datastore/settings.pb` (ARCHITECTURE §4.2).
 * Corruption → defaults + banner (FR-LIFE-04). On first use it migrates the M0 `entry_flags.bin` value
 * into field 3 and deletes that file, and sets `install_id` when it is missing or invalid.
 */
class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<ProtoSettings>,
) : SettingsRepository {
    override val settings: Flow<Settings> =
        dataStore.data.map(SettingsMapper::toModel).distinctUntilChanged()

    override suspend fun update(transform: (Settings) -> Settings) {
        dataStore.updateData { proto ->
            val old = SettingsMapper.toModel(proto)
            SettingsMapper.applyChanges(proto, old, transform(old))
        }
    }

    companion object {
        const val FILE_NAME = "settings.pb"

        /** The M0 temporary store (`filesDir/datastore/entry_flags.bin`), migrated and deleted. */
        const val LEGACY_ENTRY_FLAGS = "entry_flags.bin"

        fun create(
            context: Context,
            scope: CoroutineScope,
            resetNotices: ResetNotices,
        ): DataStoreSettingsRepository {
            val app = context.applicationContext
            return create(
                scope = scope,
                resetNotices = resetNotices,
                legacyFlags = { app.dataStoreFile(LEGACY_ENTRY_FLAGS) },
                produceFile = { app.dataStoreFile(FILE_NAME) },
            )
        }

        /** Files are resolved lazily on [scope], never on the main thread. */
        fun create(
            scope: CoroutineScope,
            resetNotices: ResetNotices,
            legacyFlags: () -> File,
            produceFile: () -> File,
        ): DataStoreSettingsRepository =
            DataStoreSettingsRepository(
                DataStoreFactory.create(
                    serializer = SettingsSerializer,
                    corruptionHandler =
                        ReplaceFileCorruptionHandler {
                            resetNotices.report(ResetCause.SETTINGS_CORRUPT)
                            ProtoSettings.getDefaultInstance()
                        },
                    migrations = listOf(LegacyEntryFlagsMigration(legacyFlags), InstallIdMigration),
                    scope = scope,
                    produceFile = produceFile,
                ),
            )
    }
}

internal object SettingsSerializer : Serializer<ProtoSettings> {
    override val defaultValue: ProtoSettings = ProtoSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): ProtoSettings =
        try {
            ProtoSettings.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("settings.pb is not a valid Settings message", e)
        }

    override suspend fun writeTo(
        t: ProtoSettings,
        output: OutputStream,
    ) = t.writeTo(output)
}

/** Field 2 is set on first start (ARCHITECTURE §4.2). */
internal object InstallIdMigration : DataMigration<ProtoSettings> {
    override suspend fun shouldMigrate(currentData: ProtoSettings): Boolean =
        !currentData.hasInstallId() || !SettingsMapper.isUuidV4(currentData.installId)

    override suspend fun migrate(currentData: ProtoSettings): ProtoSettings =
        currentData.toBuilder().setInstallId(SettingsMapper.newInstallId()).build()

    override suspend fun cleanUp() = Unit
}

/**
 * M0 kept `onboarding_done` in a two-byte file (format version 1, then 0/1). M1 copies a `true` into
 * field 3 and deletes the file. An unreadable legacy file is dropped (the flag defaults to false).
 */
internal class LegacyEntryFlagsMigration(private val legacyFile: () -> File) : DataMigration<ProtoSettings> {
    override suspend fun shouldMigrate(currentData: ProtoSettings): Boolean = legacyFile().exists()

    override suspend fun migrate(currentData: ProtoSettings): ProtoSettings {
        val bytes = runCatching { legacyFile().readBytes() }.getOrNull() ?: return currentData
        val done = bytes.size == 2 && bytes[0].toInt() == 1 && bytes[1].toInt() == 1
        if (!done || currentData.onboardingDone) return currentData
        return currentData.toBuilder().setOnboardingDone(true).build()
    }

    override suspend fun cleanUp() {
        legacyFile().delete()
    }
}
