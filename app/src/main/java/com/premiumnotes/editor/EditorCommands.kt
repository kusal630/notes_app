package com.premiumnotes.editor

import com.premiumnotes.model.PageContent
import com.premiumnotes.model.ShapeObject
import com.premiumnotes.model.Stroke

/**
 * Command-based undo/redo (requirement 14). Each command knows how to apply itself to a
 * [PageContent] and how to produce its inverse, so undo is exact rather than a
 * screenshot history.
 */
sealed interface EditorCommand {
    fun apply(content: PageContent): PageContent
    fun invert(): EditorCommand
    /** Whether this command can replace the top of the undo stack (live-drag coalescing). */
    fun canCoalesceWith(other: EditorCommand): Boolean = false
}

class AddStrokeCommand(val stroke: Stroke) : EditorCommand {
    override fun apply(content: PageContent) =
        content.copy(strokes = content.strokes + stroke)

    override fun invert() = RemoveStrokeCommand(stroke)
}

class RemoveStrokeCommand(val stroke: Stroke) : EditorCommand {
    override fun apply(content: PageContent) =
        content.copy(strokes = content.strokes.filterNot { it.id == stroke.id })

    override fun invert() = AddStrokeCommand(stroke)
}

/** Removes several strokes at once (eraser / selection delete); re-adds on undo. */
class RemoveStrokesCommand(val strokes: List<Stroke>) : EditorCommand {
    override fun apply(content: PageContent): PageContent {
        val ids = strokes.mapTo(HashSet()) { it.id }
        return content.copy(strokes = content.strokes.filterNot { ids.contains(it.id) })
    }

    override fun invert() = AddStrokesCommand(strokes)
}

class AddStrokesCommand(val strokes: List<Stroke>) : EditorCommand {
    override fun apply(content: PageContent) =
        content.copy(strokes = content.strokes + strokes)

    override fun invert() = RemoveStrokesCommand(strokes)
}

class AddShapeCommand(val shape: ShapeObject) : EditorCommand {
    override fun apply(content: PageContent) =
        content.copy(shapeObjects = content.shapeObjects + shape)

    override fun invert() = RemoveShapeCommand(shape)
}

class RemoveShapeCommand(val shape: ShapeObject) : EditorCommand {
    override fun apply(content: PageContent) =
        content.copy(shapeObjects = content.shapeObjects.filterNot { it.id == shape.id })

    override fun invert() = AddShapeCommand(shape)
}

/**
 * Batch transform (move/scale/rotate) of a set of strokes. Keeps the original
 * geometry so undo is exact; coalesces across live drag updates so a whole drag
 * becomes a single undo step.
 */
class TransformStrokesCommand(
    val originals: List<Stroke>,
    val transformed: List<Stroke>,
) : EditorCommand {
    private val ids = originals.mapTo(HashSet()) { it.id }

    override fun apply(content: PageContent): PageContent {
        val byId = HashMap<Long, Stroke>()
        for (s in transformed) byId[s.id] = s
        return content.copy(strokes = content.strokes.map { byId[it.id] ?: it })
    }

    override fun invert() = TransformStrokesCommand(transformed, originals)

    override fun canCoalesceWith(other: EditorCommand): Boolean =
        other is TransformStrokesCommand && other.ids == ids
}

/**
 * Bounded undo/redo stacks. Keeps memory small for large notebooks (default 200
 * commands) and drops the redo stack on new edits, matching standard editor behavior.
 */
class UndoRedoStack(private val limit: Int = 200) {
    private val undo = ArrayDeque<EditorCommand>()
    private val redo = ArrayDeque<EditorCommand>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(command: EditorCommand) {
        undo.addLast(command)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    /** Like [push], but replaces the top command when it can coalesce (e.g. live drags). */
    fun coalescePush(command: EditorCommand) {
        val top = undo.lastOrNull()
        if (top != null && top.canCoalesceWith(command)) {
            undo.removeLast()
        }
        push(command)
    }

    /** Returns the command to un-apply, or null. */
    fun undoCommand(): EditorCommand? {
        val c = undo.removeLastOrNull() ?: return null
        redo.addLast(c)
        return c
    }

    /** Returns the command to re-apply, or null. */
    fun redoCommand(): EditorCommand? {
        val c = redo.removeLastOrNull() ?: return null
        undo.addLast(c)
        return c
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}