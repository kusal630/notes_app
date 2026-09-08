package com.vellum.notes.data

import com.vellum.notes.model.BookHighlight
import com.vellum.notes.model.Category
import com.vellum.notes.model.Notebook
import com.vellum.notes.model.NoteType
import com.vellum.notes.model.PageContent
import com.vellum.notes.model.PageSummary
import com.vellum.notes.model.PageVersion
import com.vellum.notes.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for notebook/page catalog and page content. The in-memory
 * implementation is replaced by a Room-backed implementation in the persistence
 * milestone; the editor and UI depend only on this interface.
 */
interface NotesRepository {
    val notebooks: Flow<List<Notebook>>

    suspend fun createNotebook(
        title: String,
        type: NoteType = NoteType.NORMAL,
        coverId: String = "TEAL",
        defaultTemplate: String = "BLANK",
    ): Long
    suspend fun renameNotebook(id: Long, title: String)
    /** Soft delete: moves the notebook to Trash (restorable). */
    suspend fun deleteNotebook(id: Long)
    /** Restores a trashed notebook. */
    suspend fun restoreNotebook(id: Long)
    /** Permanent deletion (pages cascade). Only Trash and failed imports use this. */
    suspend fun deleteNotebookPermanently(id: Long)
    /** Permanently deletes everything in Trash. */
    suspend fun emptyTrash()
    /** Trashed notebooks, newest first. */
    val trashedNotebooks: Flow<List<Notebook>>
    suspend fun duplicateNotebook(id: Long): Long
    suspend fun toggleFavorite(id: Long)
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun setNotebookCover(id: Long, coverId: String)
    suspend fun setNotebookDefaultTemplate(id: Long, templateId: String)
    suspend fun setNotebookCategory(id: Long, categoryId: Long?)

    /** User-defined categories with live notebook counts, sorted A–Z. */
    val categories: Flow<List<Category>>
    suspend fun createCategory(name: String): Long
    suspend fun renameCategory(id: Long, name: String)
    /** Deletes a category; its notebooks become Unfiled (never deleted). */
    suspend fun deleteCategory(id: Long)

    fun pagesFor(notebookId: Long): Flow<List<PageSummary>>
    suspend fun createPage(
        notebookId: Long,
        title: String = "Untitled Page",
        templateId: String? = null,
    ): Long
    suspend fun deletePage(pageId: Long)
    suspend fun duplicatePage(pageId: Long): Long
    suspend fun renamePage(pageId: Long, title: String)
    suspend fun reorderPage(pageId: Long, newOrder: Int)
    suspend fun setPageTemplate(pageId: Long, templateId: String)
    suspend fun setPagePdfBackground(pageId: Long, pdfPageIndex: Int, pdfBackgroundPath: String)

    suspend fun loadPageContent(pageId: Long): PageContent?
    suspend fun savePageContent(pageId: Long, content: PageContent)
    suspend fun getNotebook(id: Long): Notebook?
    suspend fun getPage(pageId: Long): PageSummary?

    /** All read-mode highlights, newest first (review list). */
    val allHighlights: Flow<List<BookHighlight>>

    /** Highlights of one book, oldest first (read-mode overlay). */
    fun highlightsForNotebook(notebookId: Long): Flow<List<BookHighlight>>

    suspend fun addHighlight(highlight: BookHighlight): Long
    suspend fun deleteHighlight(id: Long)
    suspend fun clearPageHighlights(pageId: Long)

    /** All tags with live notebook counts, sorted A–Z. */
    val allTags: Flow<List<Tag>>

    /** Tags on one notebook, sorted A–Z. */
    suspend fun tagsForNotebook(notebookId: Long): List<Tag>

    /** Creates a tag (reuses the existing one case-insensitively). */
    suspend fun createTag(name: String): Long
    suspend fun renameTag(id: Long, name: String)
    suspend fun deleteTag(id: Long)

    /** Replaces a notebook's tag set. */
    suspend fun setNotebookTags(notebookId: Long, tagIds: Set<Long>)

    /**
     * Notebook ids with at least one page whose title or text matches [query]
     * (full-text search over titles, typed text, transcripts, summaries).
     */
    suspend fun searchPageTexts(query: String): List<Long>

    /**
     * Snapshots the page's current content. Skipped when nothing changed since
     * the latest snapshot; pruned to the newest [MAX_VERSIONS_PER_PAGE].
     * Safe to call liberally (page close, manual save).
     */
    suspend fun saveVersion(pageId: Long)

    /** Version snapshots of a page, newest first. */
    fun versionsForPage(pageId: Long): Flow<List<PageVersion>>

    /** Restores a snapshot's content onto its page (a normal undoable edit). */
    suspend fun restoreVersion(versionId: Long)

    suspend fun deleteVersion(versionId: Long)

    companion object {
        const val MAX_VERSIONS_PER_PAGE = 20
    }
}