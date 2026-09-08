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
    /** Owning category id, or null for Unfiled. */
    val categoryId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
)

/** A user-defined notebook category (Noteshelf sidebar). */
data class Category(
    val id: Long = 0L,
    val name: String,
    /** Live count of non-trashed notebooks in this category. */
    val notebookCount: Int = 0,
)

/** A user-defined tag (multi-label organization for notebooks). */
data class Tag(
    val id: Long = 0L,
    val name: String,
    /** Live count of non-trashed notebooks carrying this tag. */
    val notebookCount: Int = 0,
)

/** One saved snapshot of a page's content. */
data class PageVersion(
    val id: Long = 0L,
    val pageId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One freehand highlight stroke in read mode. Points are page-relative (0..1 of
 * the page image) so highlights survive crops and re-rasterization.
 */
data class BookHighlight(
    val id: Long = 0L,
    val notebookId: Long = 0L,
    val pageId: Long = 0L,
    val points: List<Float> = emptyList(),
    val colorArgb: Long = 0x66FFEB3BL,
    val createdAt: Long = System.currentTimeMillis(),
    /** Denormalized for the review list. */
    val notebookTitle: String = "",
    /** Denormalized for the review list. */
    val pageTitle: String = "",
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