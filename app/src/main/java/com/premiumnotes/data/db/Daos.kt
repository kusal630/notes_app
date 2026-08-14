package com.premiumnotes.data.db

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
        ORDER BY n.isArchived ASC, n.updatedAt DESC
        """
    )
    fun observeNotebooks(): Flow<List<NotebookRow>>

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

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: Long): NotebookEntity?
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
}