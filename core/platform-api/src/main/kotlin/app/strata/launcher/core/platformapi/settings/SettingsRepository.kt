package app.strata.launcher.core.platformapi.settings

import app.strata.launcher.core.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only owner of `settings.pb` (ARCHITECTURE §4, §4.2). Values outside their range are clamped or
 * replaced by the default on read. Implemented in `:core:data`; faked in `:testing:fakes`.
 */
interface SettingsRepository {
    /** Emits the current settings, then every change. */
    val settings: Flow<Settings>

    /** Atomic read-modify-write. Fields the transform leaves unchanged stay absent in the file. */
    suspend fun update(transform: (Settings) -> Settings)
}

/** Why a store was reset to defaults (FR-LIFE-04). */
enum class ResetCause { SETTINGS_CORRUPT, DATABASE_CORRUPT }

/** Collects storage resets for the "Some settings were reset" banner (FR-LIFE-04). */
interface ResetNotices {
    val causes: StateFlow<Set<ResetCause>>

    fun report(cause: ResetCause)

    fun dismiss()
}
