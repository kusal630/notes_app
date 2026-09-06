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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
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