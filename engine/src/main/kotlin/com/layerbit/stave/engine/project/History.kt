package com.layerbit.stave.engine.project

/**
 * Undo and redo over whole Sessions. A Session is a small immutable tree of lists, so holding
 * two hundred of them costs little; what matters is *which* changes become steps:
 *
 * - [record] closes whatever gesture is open and pushes a step. A one-shot command (Split,
 *   Delete) calls it with no gesture id: a gesture id is for folding a per-event stream, and on a
 *   command it would merge two deliberate actions into one undo.
 * - A gesture (a fader drag, a trim) calls [record] with the same id on every event; the step is
 *   replaced, not stacked, so the drag is one undo. [endGesture] closes it.
 * - [recordLate] is for a result that lands in the background - a render, a transcription, a
 *   measurement - while a gesture may be open. `record` would close the gesture and wedge the
 *   result between two halves of a drag, so the drag could not be taken back without losing the
 *   result. `recordLate` goes *under* the open gesture.
 *
 * Executed in HistoryChecks.
 */
class History(initial: Session, private val limit: Int = 200) {
    data class Step(val session: Session, val label: String)

    private val past = ArrayList<Step>()
    private val future = ArrayList<Step>()
    private var openGesture: String? = null

    var current: Session = initial
        private set

    /** The label of what Undo would take back, or null. */
    val undoLabel: String? get() = past.lastOrNull()?.label
    val redoLabel: String? get() = future.lastOrNull()?.label
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val depth: Int get() = past.size

    fun record(session: Session, label: String, gestureId: String? = null) {
        if (session == current) { if (gestureId == null) openGesture = null; return }
        if (gestureId != null && gestureId == openGesture) {
            // Same gesture: the step already on the stack keeps its "before"; only the result moves.
            current = session
            future.clear()
            return
        }
        openGesture = gestureId
        past += Step(current, label)
        if (past.size > limit) past.removeAt(0)
        future.clear()
        current = session
    }

    fun endGesture() { openGesture = null }

    /**
     * A change that arrived from the background: applied under the open gesture, so the
     * gesture's own step stays whole. [apply] receives the session the result should be put into
     * and returns it changed; it is applied to the current state *and* to the gesture's before
     * state when a gesture is open, so undoing the drag does not undo the result.
     */
    fun recordLate(label: String, apply: (Session) -> Session) {
        if (openGesture != null && past.isNotEmpty()) {
            val before = past.last()
            past[past.size - 1] = before.copy(session = apply(before.session))
            current = apply(current)
            return
        }
        val next = apply(current)
        if (next == current) return
        past += Step(current, label)
        if (past.size > limit) past.removeAt(0)
        future.clear()
        current = next
    }

    /**
     * Rewrites every held state: for a change that must reach the whole history, such as a
     * source's longer render replacing a shorter one (Squish lost a photo's rendering to any
     * undo because only the live state knew the new file).
     */
    fun amendAll(apply: (Session) -> Session) {
        for (i in past.indices) past[i] = past[i].copy(session = apply(past[i].session))
        for (i in future.indices) future[i] = future[i].copy(session = apply(future[i].session))
        current = apply(current)
    }

    fun undo(): Boolean {
        val step = past.removeLastOrNull() ?: return false
        openGesture = null
        future += Step(current, step.label)
        current = step.session
        return true
    }

    fun redo(): Boolean {
        val step = future.removeLastOrNull() ?: return false
        openGesture = null
        past += Step(current, step.label)
        current = step.session
        return true
    }

    /** Labels of the past steps, oldest first, for a history list. */
    fun labels(): List<String> = past.map { it.label }
}
