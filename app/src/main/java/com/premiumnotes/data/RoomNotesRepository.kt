package com.premiumnotes.data

import android.content.Context
import com.premiumnotes.data.db.AppDatabase
import com.premiumnotes.data.db.NotebookEntity
import com.premiumnotes.data.db.PageDao
import com.premiumnotes.data.db.PageEntity
import com.premiumnotes.model.Notebook
import com.premiumnotes.model.PageBackground
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room-backed [NotesRepository]. Page content and backgrounds are stored as JSON blobs
 * so the document model can evolve without schema migrations; catalog fields stay
 * queryable columns. Serialization uses the same [Json] instance as the editor.
 */
class RoomNotesRepository(db: AppDatabase) : NotesRepository {

    private val notebookDao = db.notebookDao()
    private val pageDao: PageDao = db.pageDao()

    override val notebooks: Flow<List<Notebook>> =
        notebookDao.observeNotebooks().map { rows ->
            rows.map { it.notebook.toModel(pageCount = it.pageCount) }
        }

    override suspend fun createNotebook(title: String): Long =
        notebookDao.insert(NotebookEntity(title = title))

    override suspend fun renameNotebook(id: Long, title: String) =
        notebookDao.rename(id, title)

    override suspend fun deleteNotebook(id: Long) =
        notebookDao.delete(id)

    override suspend fun duplicateNotebook(id: Long): Long {
        val src = notebookDao.get(id) ?: return -1L
        val now = System.currentTimeMillis()
        val newId = notebookDao.insert(
            src.copy(id = 0L, title = "${src.title} Copy", createdAt = now, updatedAt = now)
        )
        pageDao.pagesOf(id).forEach { page ->
            pageDao.insert(
                page.copy(
                    id = 0L,
                    notebookId = newId,
                    contentJson = copyContentJson(page.contentJson),
                )
            )
        }
        return newId
    }

    override suspend fun toggleFavorite(id: Long) {
        val nb = notebookDao.get(id) ?: return
        notebookDao.setFavorite(id, !nb.isFavorite)
    }

    override suspend fun setArchived(id: Long, archived: Boolean) =
        notebookDao.setArchived(id, archived)

    override fun pagesFor(notebookId: Long): Flow<List<PageSummary>> =
        pageDao.observePages(notebookId).map { pages ->
            pages.map { it.toModel() }
        }

    override suspend fun createPage(notebookId: Long, title: String): Long {
        val order = pageDao.pagesOf(notebookId).size
        return pageDao.insert(
            PageEntity(notebookId = notebookId, title = title, order = order)
        )
    }

    override suspend fun deletePage(pageId: Long) =
        pageDao.delete(pageId)

    override suspend fun duplicatePage(pageId: Long): Long {
        val src = pageDao.get(pageId) ?: return -1L
        val order = pageDao.pagesOf(src.notebookId).size
        return pageDao.insert(
            src.copy(
                id = 0L,
                title = "${src.title} Copy",
                order = order,
                contentJson = copyContentJson(src.contentJson),
            )
        )
    }

    override suspend fun renamePage(pageId: Long, title: String) =
        pageDao.rename(pageId, title)

    override suspend fun reorderPage(pageId: Long, newOrder: Int) {
        val target = pageDao.get(pageId) ?: return
        val list = pageDao.pagesOf(target.notebookId).sortedBy { it.order }
        val index = list.indexOfFirst { it.id == pageId }
        if (index < 0) return
        val mutable = list.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(newOrder.coerceIn(0, mutable.size), item)
        mutable.forEachIndexed { i, page ->
            if (page.order != i) pageDao.update(page.copy(order = i))
        }
    }

    override suspend fun loadPageContent(pageId: Long): PageContent? {
        val page = pageDao.get(pageId) ?: return null
        return runCatching { json.decodeFromString<PageContent>(page.contentJson) }
            .getOrDefault(PageContent())
    }

    override suspend fun savePageContent(pageId: Long, content: PageContent) {
        pageDao.saveContent(pageId, json.encodeToString(content))
    }

    override suspend fun getNotebook(id: Long): Notebook? =
        notebookDao.get(id)?.toModel(pageCount = pageDao.pagesOf(id).size)

    override suspend fun getPage(pageId: Long): PageSummary? =
        pageDao.get(pageId)?.toModel()

    private fun NotebookEntity.toModel(pageCount: Int): Notebook =
        Notebook(
            id = id,
            title = title,
            isFavorite = isFavorite,
            isArchived = isArchived,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pageCount = pageCount,
        )

    private fun PageEntity.toModel(): PageSummary {
        val background = runCatching {
            json.decodeFromString<PageBackground>(backgroundJson)
        }.getOrDefault(PageBackground())
        return PageSummary(
            id = id,
            notebookId = notebookId,
            title = title,
            order = order,
            background = background,
            updatedAt = updatedAt,
        )
    }

    /** Deep-copies serialized page content, resetting every object id so duplicates are independent. */
    private fun copyContentJson(contentJson: String): String {
        if (contentJson.isBlank()) return ""
        return runCatching {
            val content = json.decodeFromString<PageContent>(contentJson)
            json.encodeToString(
                content.copy(
                    strokes = content.strokes.map { it.copy(id = -1L) },
                    textObjects = content.textObjects.map { it.copy(id = -1L) },
                    imageObjects = content.imageObjects.map { it.copy(id = -1L) },
                    shapeObjects = content.shapeObjects.map { it.copy(id = -1L) },
                    // A classroom recording belongs to the original page, not the copy.
                    transcript = emptyList(),
                    summary = null,
                )
            )
        }.getOrDefault(contentJson)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Factory used by [com.premiumnotes.AppContainer]. */
fun createRepository(context: Context): NotesRepository =
    RoomNotesRepository(AppDatabase.get(context))