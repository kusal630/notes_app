package com.vellum.notes.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    /** [com.vellum.notes.model.NoteType] name, stored as text for readability. */
    val type: String = "NORMAL",
    /** [com.vellum.notes.model.NotebookCovers.Cover.id]; rendered with Compose, no assets. */
    val coverId: String = "TEAL",
    /** Per-notebook default paper template ([com.vellum.notes.model.PaperTemplates.Template.id]). */
    val defaultTemplate: String = "BLANK",
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    /** Owning category id ([CategoryEntity.id]), or null for Unfiled. */
    val categoryId: Long? = null,
    /** Trash timestamp (epoch ms), or null when not trashed. */
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A user-defined notebook category (Noteshelf sidebar). */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("notebookId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val notebookId: Long,
    val title: String = "Untitled Page",
    val order: Int = 0,
    /** Serialized [com.vellum.notes.model.PageBackground]. */
    val backgroundJson: String = "{}",
    /** Per-page paper template override ([com.vellum.notes.model.PaperTemplates.Template.id]). */
    val templateId: String = "BLANK",
    /**
     * PDF-backed page support (feature C): source page index, or -1 for a normal page.
     * The rasterized page bitmap lives in app-private storage; [pdfBackgroundPath] is the
     * relative file name under `pdf-pages/`.
     */
    val pdfPageIndex: Int = -1,
    val pdfBackgroundPath: String = "",
    /** Serialized [com.vellum.notes.model.PageContent]. */
    val contentJson: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Notebook + live page count, produced by the observing query. */
data class NotebookRow(
    @Embedded val notebook: NotebookEntity,
    val pageCount: Int = 0,
)

/** Category + live non-trashed notebook count, produced by the observing query. */
data class CategoryRow(
    @Embedded val category: CategoryEntity,
    val notebookCount: Int = 0,
)

/**
 * One freehand highlight stroke in read mode, stored in page-relative coordinates
 * (0..1 of the page image) so it survives crops, rotations and re-rasterization.
 * Cascades with both the notebook and the page.
 */
@Entity(
    tableName = "book_highlights",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("notebookId"), Index("pageId")],
)
data class BookHighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val notebookId: Long,
    val pageId: Long,
    /** JSON array of [x0,y0,x1,y1,…] in page-relative coordinates. */
    val pointsJson: String = "[]",
    val colorArgb: Long = 0x66FFEB3B,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Highlight + the titles needed for the review list, produced by the observing query. */
data class BookHighlightRow(
    @Embedded val highlight: BookHighlightEntity,
    val notebookTitle: String = "",
    val pageTitle: String = "",
)