package com.premiumnotes.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    /** [com.premiumnotes.model.NoteType] name, stored as text for readability. */
    val type: String = "NORMAL",
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
    /** Serialized [com.premiumnotes.model.PageBackground]. */
    val backgroundJson: String = "{}",
    /** Serialized [com.premiumnotes.model.PageContent]. */
    val contentJson: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Notebook + live page count, produced by the observing query. */
data class NotebookRow(
    @Embedded val notebook: NotebookEntity,
    val pageCount: Int = 0,
)