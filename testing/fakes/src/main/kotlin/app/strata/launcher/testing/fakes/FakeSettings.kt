package app.strata.launcher.testing.fakes

import app.strata.launcher.core.model.Settings
import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import app.strata.launcher.core.platformapi.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory [SettingsRepository]. */
class FakeSettingsRepository(initial: Settings = Settings.DEFAULT) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: StateFlow<Settings> = state

    val value: Settings get() = state.value

    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)

    fun set(value: Settings) {
        state.value = value
    }
}

/** In-memory [ResetNotices]. */
class FakeResetNotices : ResetNotices {
    private val state = MutableStateFlow<Set<ResetCause>>(emptySet())

    override val causes: StateFlow<Set<ResetCause>> = state

    override fun report(cause: ResetCause) = state.update { it + cause }

    override fun dismiss() {
        state.value = emptySet()
    }
}
