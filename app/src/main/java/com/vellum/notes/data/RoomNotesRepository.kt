package com.vellum.notes.data

import android.content.Context
import com.vellum.notes.data.db.AppDatabase
import com.vellum.notes.data.db.BookHighlightEntity
import com.vellum.notes.data.db.CategoryEntity
import com.vellum.notes.data.db.NotebookEntity
import com.vellum.notes.data.db.NotebookTagCrossRef
import com.vellum.notes.data.db.PageDao
import com.vellum.notes.data.db.PageEntity
import com.vellum.notes.data.db.PageSearchEntity
import com.vellum.notes.data.db.PageVersionEntity
import com.vellum.notes.data.db.TagEntity
import com.vellum.notes.model.BookHighlight
import com.vellum.notes.model.Category
import com.vellum.notes.model.Notebook
import com.vellum.notes.model.PageVersion
import com.vellum.notes.model.Tag
import com.vellum.notes.model.NoteType
import com.vellum.notes.model.PageBackground
import com.vellum.notes.model.PageContent
import com.vellum.notes.model.PageSummary
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
class RoomNotesRepository(private val db: AppDatabase) : NotesRepository {

    private val notebookDao = db.notebookDao()
    private val pageDao: PageDao = db.pageDao()
    private val categoryDao = db.categoryDao()
    private val highlightDao = db.highlightDao()
    private val tagDao = db.tagDao()
    private val pageSearchDao = db.pageSearchDao()
    private val pageVersionDao = db.pageVersionDao()

    /** Flushes the WAL into the db file so a file-level backup is consistent. */
    suspend fun checkpoint() {
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        }
    }

    override val notebooks: Flow<List<Notebook>> =
        notebookDao.observeNotebooks().map { rows ->
            rows.map { it.notebook.toModel(pageCount = it.pageCount) }
        }

    override suspend fun createNotebook(
        title: String,
        type: NoteType,
        coverId: String,
        defaultTemplate: String,
    ): Long =
        notebookDao.insert(
            NotebookEntity(
                title = title,
                type = type.name,
                coverId = coverId.ifBlank { "TEAL" },
                defaultTemplate = defaultTemplate.ifBlank { "BLANK" },
            )
        )

    override suspend fun renameNotebook(id: Long, title: String) =
        notebookDao.rename(id, title)

    override suspend fun deleteNotebook(id: Long) =
        notebookDao.trash(id)

    override suspend fun restoreNotebook(id: Long) =
        notebookDao.restore(id)

    override suspend fun deleteNotebookPermanently(id: Long) {
        pageSearchDao.deleteForNotebook(id)
        notebookDao.delete(id)
    }

    override suspend fun emptyTrash() {
        pageSearchDao.deleteForTrash()
        notebookDao.emptyTrash()
    }

    override val trashedNotebooks: Flow<List<Notebook>> =
        notebookDao.observeTrashed().map { rows ->
            rows.map { it.notebook.toModel(pageCount = it.pageCount) }
        }

    override suspend fun setNotebookCategory(id: Long, categoryId: Long?) {
        // Dropping a notebook into a missing category would orphan it: fall back
        // to Unfiled instead.
        val resolved = categoryId?.takeIf { categoryDao.get(it) != null }
        notebookDao.setCategory(id, resolved)
    }

    override val categories: Flow<List<Category>> =
        categoryDao.observeCategories().map { rows ->
            rows.map { Category(id = it.category.id, name = it.category.name, notebookCount = it.notebookCount) }
        }

    override suspend fun createCategory(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name must not be blank" }
        return categoryDao.insert(CategoryEntity(name = trimmed))
    }

    override suspend fun renameCategory(id: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Category name must not be blank" }
        categoryDao.rename(id, trimmed)
    }

    override suspend fun deleteCategory(id: Long) {
        categoryDao.delete(id)
        // Notebooks have no FK to categories, so unfile explicitly: everything
        // that pointed at the deleted category becomes Unfiled.
        notebookDao.clearCategory(id)
    }

    override suspend fun duplicateNotebook(id: Long): Long {
        val src = notebookDao.get(id) ?: return -1L
        val now = System.currentTimeMillis()
        val newId = notebookDao.insert(
            src.copy(id = 0L, title = "${src.title} Copy", createdAt = now, updatedAt = now)
        )
        pageDao.pagesOf(id).forEach { page ->
            val newPageId = pageDao.insert(
                page.copy(
                    id = 0L,
                    notebookId = newId,
                    contentJson = copyContentJson(page.contentJson),
                )
            )
            indexPage(newPageId)
        }
        return newId
    }

    override suspend fun toggleFavorite(id: Long) {
        val nb = notebookDao.get(id) ?: return
        notebookDao.setFavorite(id, !nb.isFavorite)
    }

    override suspend fun setArchived(id: Long, archived: Boolean) =
        notebookDao.setArchived(id, archived)

    override suspend fun setNotebookCover(id: Long, coverId: String) =
        notebookDao.setCover(id, coverId.ifBlank { "TEAL" })

    override suspend fun setNotebookDefaultTemplate(id: Long, templateId: String) =
        notebookDao.setDefaultTemplate(id, templateId.ifBlank { "BLANK" })

    override fun pagesFor(notebookId: Long): Flow<List<PageSummary>> =
        pageDao.observePages(notebookId).map { pages ->
            pages.map { it.toModel() }
        }

    override suspend fun createPage(notebookId: Long, title: String, templateId: String?): Long {
        val order = pageDao.pagesOf(notebookId).size
        val resolved = templateId?.ifBlank { null }
            ?: notebookDao.get(notebookId)?.defaultTemplate?.ifBlank { "BLANK" }
            ?: "BLANK"
        val id = pageDao.insert(
            PageEntity(notebookId = notebookId, title = title, order = order, templateId = resolved)
        )
        indexPage(id)
        return id
    }

    override suspend fun deletePage(pageId: Long) {
        pageSearchDao.deleteForPage(pageId)
        pageDao.delete(pageId)
    }

    override suspend fun duplicatePage(pageId: Long): Long {
        val src = pageDao.get(pageId) ?: return -1L
        val order = pageDao.pagesOf(src.notebookId).size
        val id = pageDao.insert(
            src.copy(
                id = 0L,
                title = "${src.title} Copy",
                order = order,
                contentJson = copyContentJson(src.contentJson),
            )
        )
        indexPage(id)
        return id
    }

    override suspend fun renamePage(pageId: Long, title: String) {
        pageDao.rename(pageId, title)
        indexPage(pageId)
    }

    override suspend fun setPageTemplate(pageId: Long, templateId: String) =
        pageDao.saveTemplate(pageId, templateId.ifBlank { "BLANK" })

    override suspend fun setPagePdfBackground(pageId: Long, pdfPageIndex: Int, pdfBackgroundPath: String) =
        pageDao.savePdfBackground(pageId, pdfPageIndex, pdfBackgroundPath)

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
        indexPage(pageId)
    }

    override suspend fun getNotebook(id: Long): Notebook? =
        notebookDao.get(id)?.toModel(pageCount = pageDao.pagesOf(id).size)

    override suspend fun getPage(pageId: Long): PageSummary? =
        pageDao.get(pageId)?.toModel()

    override val allHighlights: Flow<List<BookHighlight>> =
        highlightDao.observeAll().map { rows ->
            rows.map {
                it.highlight.toModel(notebookTitle = it.notebookTitle, pageTitle = it.pageTitle)
            }
        }

    override fun highlightsForNotebook(notebookId: Long): Flow<List<BookHighlight>> =
        highlightDao.observeForNotebook(notebookId).map { list ->
            list.map { it.toModel() }
        }

    override suspend fun addHighlight(highlight: BookHighlight): Long {
        require(highlight.points.size >= 4) { "A highlight needs at least 2 points" }
        return highlightDao.insert(
            BookHighlightEntity(
                notebookId = highlight.notebookId,
                pageId = highlight.pageId,
                pointsJson = json.encodeToString(highlight.points),
                colorArgb = highlight.colorArgb,
            )
        )
    }

    override suspend fun deleteHighlight(id: Long) =
        highlightDao.delete(id)

    override suspend fun clearPageHighlights(pageId: Long) =
        highlightDao.clearPage(pageId)

    override val allTags: Flow<List<Tag>> =
        tagDao.observeTags().map { rows ->
            rows.map { Tag(id = it.tag.id, name = it.tag.name, notebookCount = it.notebookCount) }
        }

    override suspend fun tagsForNotebook(notebookId: Long): List<Tag> =
        tagDao.tagsForNotebook(notebookId).map { Tag(id = it.id, name = it.name) }

    override suspend fun createTag(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Tag name must not be blank" }
        return tagDao.findByName(trimmed)?.id ?: tagDao.insert(TagEntity(name = trimmed))
    }

    override suspend fun renameTag(id: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Tag name must not be blank" }
        tagDao.rename(id, trimmed)
    }

    override suspend fun deleteTag(id: Long) =
        tagDao.delete(id)

    override suspend fun setNotebookTags(notebookId: Long, tagIds: Set<Long>) {
        tagDao.clearNotebook(notebookId)
        tagIds.forEach { tagDao.assign(NotebookTagCrossRef(notebookId, it)) }
    }

    override suspend fun searchPageTexts(query: String): List<Long> {
        val match = SearchIndex.sanitizeQuery(query)
        if (match.isBlank()) return emptyList()
        return pageSearchDao.searchNotebookIds(match)
    }

    override suspend fun saveVersion(pageId: Long) {
        val page = pageDao.get(pageId) ?: return
        val latest = pageVersionDao.latestForPage(pageId)
        // Identical consecutive snapshots are noise: skip them so liberal
        // callers (page close, manual save) cannot spam history.
        if (latest != null && latest.contentJson == page.contentJson) return
        pageVersionDao.insert(
            PageVersionEntity(pageId = pageId, contentJson = page.contentJson)
        )
        pageVersionDao.prune(pageId, NotesRepository.MAX_VERSIONS_PER_PAGE)
    }

    override fun versionsForPage(pageId: Long): Flow<List<PageVersion>> =
        pageVersionDao.observeForPage(pageId).map { list ->
            list.map { PageVersion(id = it.id, pageId = it.pageId, createdAt = it.createdAt) }
        }

    override suspend fun restoreVersion(versionId: Long) {
        val version = pageVersionDao.get(versionId) ?: return
        val content = runCatching {
            json.decodeFromString<PageContent>(version.contentJson)
        }.getOrNull() ?: return
        // Snapshot the present first so the restore itself stays reversible.
        saveVersion(version.pageId)
        // A restore is a normal content write (re-indexed for search).
        savePageContent(version.pageId, content)
    }

    override suspend fun deleteVersion(versionId: Long) =
        pageVersionDao.delete(versionId)

    /** Rebuilds the full-text row for one page (title + current content). */
    private suspend fun indexPage(pageId: Long) {
        val page = pageDao.get(pageId) ?: return
        pageSearchDao.deleteForPage(pageId)
        val content = loadPageContent(pageId) ?: PageContent()
        pageSearchDao.insert(
            PageSearchEntity(
                pageId = pageId,
                notebookId = page.notebookId,
                title = page.title,
                body = SearchIndex.bodyFor(content),
            )
        )
    }

    private fun BookHighlightEntity.toModel(
        notebookTitle: String = "",
        pageTitle: String = "",
    ): BookHighlight {
        val points = runCatching {
            json.decodeFromString<List<Float>>(pointsJson)
        }.getOrDefault(emptyList())
        return BookHighlight(
            id = id,
            notebookId = notebookId,
            pageId = pageId,
            points = points,
            colorArgb = colorArgb,
            createdAt = createdAt,
            notebookTitle = notebookTitle,
            pageTitle = pageTitle,
        )
    }

    private fun NotebookEntity.toModel(pageCount: Int): Notebook =
        Notebook(
            id = id,
            title = title,
            type = runCatching { NoteType.valueOf(type) }.getOrDefault(NoteType.NORMAL),
            coverId = coverId.ifBlank { "TEAL" },
            defaultTemplate = defaultTemplate.ifBlank { "BLANK" },
            isFavorite = isFavorite,
            isArchived = isArchived,
            categoryId = categoryId,
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
            templateId = templateId.ifBlank { "BLANK" },
            pdfPageIndex = pdfPageIndex,
            pdfBackgroundPath = pdfBackgroundPath,
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

/** Factory used by [com.vellum.notes.AppContainer]. */
fun createRepository(context: Context): NotesRepository =
    RoomNotesRepository(AppDatabase.get(context))