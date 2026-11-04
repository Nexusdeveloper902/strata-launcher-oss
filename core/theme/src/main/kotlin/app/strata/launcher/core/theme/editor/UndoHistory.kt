package app.strata.launcher.core.theme.editor

/**
 * Per-session undo/redo over immutable snapshots (THEME_SYSTEM §13). Holds at least [capacity] steps (oldest dropped
 * beyond it). Edits that carry the same coalescing key while a coalescing run is open (a slider drag, one text-field
 * edit session) replace the newest snapshot instead of adding a step; [endCoalescing] closes the run.
 */
class UndoHistory<T : Any>(
    initial: T,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity >= MIN_CAPACITY) { "capacity below the §13 minimum" }
    }

    var current: T = initial
        private set

    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var openKey: String? = null

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoDepth: Int get() = undo.size
    val redoDepth: Int get() = redo.size

    /**
     * Records [next] as the new state. With [coalesceKey] equal to the open run's key, the run's step is updated in
     * place. Returns false when nothing changed.
     */
    fun record(
        next: T,
        coalesceKey: String? = null,
    ): Boolean {
        if (coalesceKey != null && coalesceKey == openKey && undo.isNotEmpty()) {
            if (next == current) return false
            current = next
            redo.clear()
            // A drag that returns to where it started is no step at all.
            if (undo.last() == current) {
                undo.removeLast()
                openKey = null
            }
            return true
        }
        if (next == current) return false
        // The run opens only with a real step, so its first in-place update never rewrites an earlier edit.
        openKey = coalesceKey
        undo.addLast(current)
        while (undo.size > capacity) undo.removeFirst()
        redo.clear()
        current = next
        return true
    }

    /** Ends the open coalescing run (drag released, text field left). */
    fun endCoalescing() {
        openKey = null
    }

    fun undo(): Boolean {
        openKey = null
        val prev = undo.removeLastOrNull() ?: return false
        redo.addLast(current)
        current = prev
        return true
    }

    fun redo(): Boolean {
        openKey = null
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(current)
        while (undo.size > capacity) undo.removeFirst()
        current = next
        return true
    }

    companion object {
        /** §13 asks for ≥ 100 steps; 200 leaves room (Proposed). */
        const val DEFAULT_CAPACITY = 200
        const val MIN_CAPACITY = 100
    }
}
