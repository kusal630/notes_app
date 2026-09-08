package com.vellum.notes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    @Query(
        """
        SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
        FROM notebooks n
        WHERE n.deletedAt IS NULL
        ORDER BY n.isArchived ASC, n.updatedAt DESC
        """
    )
    fun observeNotebooks(): Flow<List<NotebookRow>>

    @Query(
        """
        SELECT n.*, (SELECT COUNT(*) FROM pages p WHERE p.notebookId = n.id) AS pageCount
        FROM notebooks n
        WHERE n.deletedAt IS NOT NULL
        ORDER BY n.deletedAt DESC
        """
    )
    fun observeTrashed(): Flow<List<NotebookRow>>

    @Insert
    suspend fun insert(notebook: NotebookEntity): Long

    @Update
    suspend fun update(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE notebooks SET isFavorite = :fav, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE notebooks SET isArchived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE notebooks SET coverId = :coverId, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, coverId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE notebooks SET defaultTemplate = :templateId, updatedAt = :now WHERE id = :id")
    suspend fun setDefaultTemplate(id: Long, templateId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE notebooks SET categoryId = :categoryId, updatedAt = :now WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?, now: Long = System.currentTimeMillis())

    /** Unfiles every notebook in a deleted category (they are never deleted). */
    @Query("UPDATE notebooks SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    /** Soft delete: flags the notebook; pages stay untouched until permanent deletion cascades. */
    @Query("UPDATE notebooks SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun trash(id: Long, now: Long = System.currentTimeMillis())

    /** Restores a trashed notebook. */
    @Query("UPDATE notebooks SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notebooks WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: Long): NotebookEntity?
}

@Dao
interface CategoryDao {

    @Query(
        """
        SELECT c.*, (SELECT COUNT(*) FROM notebooks n
                     WHERE n.categoryId = c.id AND n.deletedAt IS NULL) AS notebookCount
        FROM categories c
        ORDER BY c.name COLLATE NOCASE ASC
        """
    )
    fun observeCategories(): Flow<List<CategoryRow>>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY `order` ASC")
    fun observePages(notebookId: Long): Flow<List<PageEntity>>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Update
    suspend fun update(page: PageEntity)

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: Long): PageEntity?

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY `order` ASC")
    suspend fun pagesOf(notebookId: Long): List<PageEntity>

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pages SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET contentJson = :contentJson, updatedAt = :now WHERE id = :id")
    suspend fun saveContent(id: Long, contentJson: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET backgroundJson = :backgroundJson, updatedAt = :now WHERE id = :id")
    suspend fun saveBackground(id: Long, backgroundJson: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET templateId = :templateId, updatedAt = :now WHERE id = :id")
    suspend fun saveTemplate(id: Long, templateId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET pdfPageIndex = :pdfPageIndex, pdfBackgroundPath = :pdfBackgroundPath, updatedAt = :now WHERE id = :id")
    suspend fun savePdfBackground(id: Long, pdfPageIndex: Int, pdfBackgroundPath: String, now: Long = System.currentTimeMillis())
}