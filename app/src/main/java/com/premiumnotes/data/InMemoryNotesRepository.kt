package com.premiumnotes.data

import android.content.Context
import com.premiumnotes.model.Notebook
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Temporary in-memory repository so the UI can be built and exercised before the
 * Room-backed implementation lands in the persistence milestone. Same contract as
 * [NotesRepository]; the swap is transparent to callers.
 */
class InMemoryNotesRepository : NotesRepository {

    private val _notebooks = MutableStateFlow<List<Notebook>>(emptyList())
    override val notebooks: Flow<List<Notebook>> = _notebooks

    private val pages = HashMap<Long, MutableList<PageSummary>>()
    private val contents = HashMap<Long, PageContent>()
    private val pageNotebook = HashMap<Long, Long>()

    private var nextNotebookId = 1L
    private var nextPageId = 1L

    override suspend fun createNotebook(title: String): Long {
        val id = nextNotebookId++
        val nb = Notebook(id = id, title = title)
        _notebooks.value = _notebooks.value + nb
        pages[id] = mutableListOf()
        return id
    }

    override suspend fun renameNotebook(id: Long, title: String) {
        _notebooks.value = _notebooks.value.map {
            if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it
        }
    }

    override suspend fun deleteNotebook(id: Long) {
        _notebooks.value = _notebooks.value.filterNot { it.id == id }
        pages.remove(id)
    }

    override suspend fun duplicateNotebook(id: Long): Long {
        val src = _notebooks.value.firstOrNull { it.id == id } ?: return -1L
        val newId = nextNotebookId++
        _notebooks.value = _notebooks.value + src.copy(id = newId, title = "${src.title} Copy")
        val newPages = pages[id]?.map { it.copy(id = nextPageId++, order = it.order) } ?: emptyList()
        pages[newId] = newPages.toMutableList()
        newPages.forEach { p ->
            pageNotebook[p.id] = newId
            contents[p.id] = contents[pages[id]?.first { it.order == p.order }?.id]
                ?.let { copyContent(it) } ?: PageContent()
        }
        return newId
    }

    private fun copyContent(c: PageContent): PageContent = PageContent(
        contentVersion = c.contentVersion,
        strokes = c.strokes.map { it.copy(id = -1L) },
        textObjects = c.textObjects.map { it.copy(id = -1L) },
        imageObjects = c.imageObjects.map { it.copy(id = -1L) },
        shapeObjects = c.shapeObjects.map { it.copy(id = -1L) },
    )

    override suspend fun toggleFavorite(id: Long) {
        _notebooks.value = _notebooks.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
        }
    }

    override suspend fun setArchived(id: Long, archived: Boolean) {
        _notebooks.value = _notebooks.value.map {
            if (it.id == id) it.copy(isArchived = archived) else it
        }
    }

    override fun pagesFor(notebookId: Long): Flow<List<PageSummary>> =
        MutableStateFlow(pages[notebookId]?.toList() ?: emptyList())

    override suspend fun createPage(notebookId: Long, title: String): Long {
        val list = pages.getOrPut(notebookId) { mutableListOf() }
        val id = nextPageId++
        val page = PageSummary(id = id, notebookId = notebookId, title = title, order = list.size)
        list += page
        pageNotebook[id] = notebookId
        contents[id] = PageContent()
        return id
    }

    override suspend fun deletePage(pageId: Long) {
        val nbId = pageNotebook.remove(pageId)
        if (nbId != null) {
            pages[nbId]?.removeAll { it.id == pageId }
        }
        contents.remove(pageId)
    }

    override suspend fun duplicatePage(pageId: Long): Long {
        val src = contents[pageId] ?: return -1L
        val nbId = pageNotebook[pageId] ?: return -1L
        val list = pages.getOrPut(nbId) { mutableListOf() }
        val newId = nextPageId++
        list += PageSummary(id = newId, notebookId = nbId, title = "Copy", order = list.size)
        pageNotebook[newId] = nbId
        contents[newId] = copyContent(src)
        return newId
    }

    override suspend fun renamePage(pageId: Long, title: String) {
        val nbId = pageNotebook[pageId] ?: return
        pages[nbId] = pages[nbId]?.map { if (it.id == pageId) it.copy(title = title) else it }
            ?.toMutableList() ?: mutableListOf()
    }

    override suspend fun reorderPage(pageId: Long, newOrder: Int) {
        val nbId = pageNotebook[pageId] ?: return
        val list = pages[nbId] ?: return
        val sorted = list.sortedBy { it.order }
        val idx = sorted.indexOfFirst { it.id == pageId }
        if (idx < 0) return
        val mutable = sorted.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(newOrder.coerceIn(0, mutable.size), item)
        mutable.forEachIndexed { i, p -> list[list.indexOfFirst { it.id == p.id }] = p.copy(order = i) }
    }

    override suspend fun loadPageContent(pageId: Long): PageContent? = contents[pageId]

    override suspend fun savePageContent(pageId: Long, content: PageContent) {
        contents[pageId] = content
        val nbId = pageNotebook[pageId]
        if (nbId != null) {
            pages[nbId] = pages[nbId]?.map { if (it.id == pageId) it.copy(updatedAt = System.currentTimeMillis()) else it }
                ?.toMutableList() ?: mutableListOf()
        }
    }

    override suspend fun getNotebook(id: Long): Notebook? = _notebooks.value.firstOrNull { it.id == id }

    override suspend fun getPage(pageId: Long): PageSummary? {
        val nbId = pageNotebook[pageId] ?: return null
        return pages[nbId]?.firstOrNull { it.id == pageId }
    }
}

/** Convenience factory used by [AppContainer]; will delegate to the Room repo later. */
fun createRepository(context: Context): NotesRepository = InMemoryNotesRepository()