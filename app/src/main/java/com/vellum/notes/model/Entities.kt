package com.vellum.notes.model

/** The kind of notebook a note belongs to. */
enum class NoteType {
    /** A regular handwritten note (no audio/transcript). */
    NORMAL,

    /** A classroom note: the usual canvas plus an on-device audio transcript sidebar. */
    CLASSROOM,
}

/** Catalog-level notebook record (mirrors the eventual Room entity). */
data class Notebook(
    val id: Long = 0L,
    val title: String,
    val type: NoteType = NoteType.NORMAL,
    val coverId: String = "TEAL",
    val defaultTemplate: String = "BLANK",
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
    val templateId: String = "BLANK",
    /** Source PDF page index, or -1 for a normal page. */
    val pdfPageIndex: Int = -1,
    /** Relative file name of the rasterized PDF page under `pdf-pages/`. */
    val pdfBackgroundPath: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isPdfBacked: Boolean get() = pdfPageIndex >= 0
}