package com.premiumnotes.editor

import com.premiumnotes.model.ImageObject
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.ShapeObject
import com.premiumnotes.model.Stroke
import com.premiumnotes.model.TextObject

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
 * Batch transform (move/scale/rotate) of a set of strokes AND shapes. Keeps the
 * original geometry so undo is exact; coalesces across live drag/resize updates so a
 * whole drag or resize becomes a single undo step.
 */
class TransformSelectionCommand(
    val originalStrokes: List<Stroke>,
    val transformedStrokes: List<Stroke>,
    val originalShapes: List<ShapeObject>,
    val transformedShapes: List<ShapeObject>,
) : EditorCommand {
    private val ids: Set<Long> = buildSet {
        originalStrokes.forEach { add(it.id) }
        originalShapes.forEach { add(it.id) }
    }

    override fun apply(content: PageContent): PageContent {
        val strokeById = HashMap<Long, Stroke>()
        for (s in transformedStrokes) strokeById[s.id] = s
        val shapeById = HashMap<Long, ShapeObject>()
        for (s in transformedShapes) shapeById[s.id] = s
        return content.copy(
            strokes = content.strokes.map { strokeById[it.id] ?: it },
            shapeObjects = content.shapeObjects.map { shapeById[it.id] ?: it },
        )
    }

    override fun invert() = TransformSelectionCommand(
        transformedStrokes, originalStrokes, transformedShapes, originalShapes,
    )

    override fun canCoalesceWith(other: EditorCommand): Boolean =
        other is TransformSelectionCommand && other.ids == ids
}

/** Removes any combination of object types at once; re-adds them on undo. */
class RemoveObjectsCommand(
    val strokes: List<Stroke> = emptyList(),
    val shapes: List<ShapeObject> = emptyList(),
    val textObjects: List<TextObject> = emptyList(),
    val imageObjects: List<ImageObject> = emptyList(),
) : EditorCommand {
    private val strokeIds = strokes.mapTo(HashSet()) { it.id }
    private val shapeIds = shapes.mapTo(HashSet()) { it.id }
    private val textIds = textObjects.mapTo(HashSet()) { it.id }
    private val imageIds = imageObjects.mapTo(HashSet()) { it.id }

    override fun apply(content: PageContent): PageContent = content.copy(
        strokes = content.strokes.filterNot { it.id in strokeIds },
        shapeObjects = content.shapeObjects.filterNot { it.id in shapeIds },
        textObjects = content.textObjects.filterNot { it.id in textIds },
        imageObjects = content.imageObjects.filterNot { it.id in imageIds },
    )

    override fun invert() = AddObjectsCommand(strokes, shapes, textObjects, imageObjects)
}

class AddObjectsCommand(
    val strokes: List<Stroke> = emptyList(),
    val shapes: List<ShapeObject> = emptyList(),
    val textObjects: List<TextObject> = emptyList(),
    val imageObjects: List<ImageObject> = emptyList(),
) : EditorCommand {
    override fun apply(content: PageContent): PageContent = content.copy(
        strokes = content.strokes + strokes,
        shapeObjects = content.shapeObjects + shapes,
        textObjects = content.textObjects + textObjects,
        imageObjects = content.imageObjects + imageObjects,
    )

    override fun invert() = RemoveObjectsCommand(strokes, shapes, textObjects, imageObjects)
}

/** Adds several shapes at once (duplication, undo of a batch erase). */
class AddShapesCommand(val shapes: List<ShapeObject>) : EditorCommand {
    override fun apply(content: PageContent) =
        content.copy(shapeObjects = content.shapeObjects + shapes)

    override fun invert() = RemoveShapesCommand(shapes)
}

class RemoveShapesCommand(val shapes: List<ShapeObject>) : EditorCommand {
    override fun apply(content: PageContent): PageContent {
        val ids = shapes.mapTo(HashSet()) { it.id }
        return content.copy(shapeObjects = content.shapeObjects.filterNot { ids.contains(it.id) })
    }

    override fun invert() = AddShapesCommand(shapes)
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