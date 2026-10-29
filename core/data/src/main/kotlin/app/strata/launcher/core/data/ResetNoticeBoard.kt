package app.strata.launcher.core.data

import app.strata.launcher.core.platformapi.settings.ResetCause
import app.strata.launcher.core.platformapi.settings.ResetNotices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory [ResetNotices]: the banner is shown for this process only (FR-LIFE-04). */
class ResetNoticeBoard : ResetNotices {
    private val state = MutableStateFlow<Set<ResetCause>>(emptySet())

    override val causes: StateFlow<Set<ResetCause>> = state

    override fun report(cause: ResetCause) = state.update { it + cause }

    override fun dismiss() {
        state.value = emptySet()
    }
}
