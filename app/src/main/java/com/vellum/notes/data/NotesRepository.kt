package com.vellum.notes.data

import com.vellum.notes.model.Notebook
import com.vellum.notes.model.NoteType
import com.vellum.notes.model.PageContent
import com.vellum.notes.model.PageSummary
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
    suspend fun deleteNotebook(id: Long)
    suspend fun duplicateNotebook(id: Long): Long
    suspend fun toggleFavorite(id: Long)
    suspend fun setArchived(id: Long, archived: Boolean)
    suspend fun setNotebookCover(id: Long, coverId: String)
    suspend fun setNotebookDefaultTemplate(id: Long, templateId: String)

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
}