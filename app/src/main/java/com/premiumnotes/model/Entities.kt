package com.premiumnotes.model

/** Catalog-level notebook record (mirrors the eventual Room entity). */
data class Notebook(
    val id: Long = 0L,
    val title: String,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
)

/** Catalog-level page record. */
data class PageSummary(
    val id: Long = 0L,
    val notebookId: Long = 0L,
    val title: String = "Untitled Page",
    val order: Int = 0,
    val background: PageBackground = PageBackground(),
    val updatedAt: Long = System.currentTimeMillis(),
)