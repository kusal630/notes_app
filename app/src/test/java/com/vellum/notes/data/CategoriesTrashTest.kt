package com.vellum.notes.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.vellum.notes.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Categories + Trash (Noteshelf organization): CRUD, live counts, soft delete /
 * restore / permanent delete / empty trash, unfiling on category delete, and
 * the v3 → v4 migration preserving existing notebooks.
 */
@RunWith(RobolectricTestRunner::class)
class CategoriesTrashTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomNotesRepository

    @Before
    fun setup() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomNotesRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun categories_crudWithLiveCounts() = runBlocking {
        val school = repo.createCategory("School")
        val gym = repo.createCategory("Gym")
        val a = repo.createNotebook("Physics")
        val b = repo.createNotebook("Chemistry")
        repo.setNotebookCategory(a, school)
        repo.setNotebookCategory(b, school)

        val cats = repo.categories.first()
        assertEquals(listOf("Gym", "School"), cats.map { it.name })
        assertEquals(2, cats.first { it.id == school }.notebookCount)
        assertEquals(0, cats.first { it.id == gym }.notebookCount)

        repo.renameCategory(gym, "Sports")
        assertTrue(repo.categories.first().any { it.name == "Sports" })

        // Deleting a category unfiles its notebooks instead of deleting them.
        repo.deleteCategory(school)
        assertEquals(1, repo.categories.first().size)
        assertNull(repo.getNotebook(a)?.categoryId)
        assertNull(repo.getNotebook(b)?.categoryId)
        assertEquals(2, repo.notebooks.first().size)
    }

    @Test
    fun setNotebookCategory_unknownCategory_fallsBackToUnfiled() = runBlocking {
        val a = repo.createNotebook("Orphan")
        repo.setNotebookCategory(a, 9999L)
        assertNull(repo.getNotebook(a)?.categoryId)
    }

    @Test
    fun trash_restore_permanentDelete_emptyTrash() = runBlocking {
        val a = repo.createNotebook("Keep")
        val b = repo.createNotebook("Toss")
        val c = repo.createNotebook("Toss too")

        repo.deleteNotebook(b)
        repo.deleteNotebook(c)
        assertEquals(1, repo.notebooks.first().size)
        assertEquals(setOf("Toss", "Toss too"), repo.trashedNotebooks.first().map { it.title }.toSet())

        repo.restoreNotebook(b)
        assertEquals(2, repo.notebooks.first().size)
        assertEquals(listOf("Toss too"), repo.trashedNotebooks.first().map { it.title })

        repo.deleteNotebookPermanently(c)
        assertTrue(repo.trashedNotebooks.first().isEmpty())

        repo.deleteNotebook(a)
        repo.deleteNotebook(b)
        assertTrue(repo.notebooks.first().isEmpty())
        assertEquals(2, repo.trashedNotebooks.first().size)
        repo.emptyTrash()
        assertTrue(repo.trashedNotebooks.first().isEmpty())
        assertNull(repo.getNotebook(a))
        assertNull(repo.getNotebook(b))
    }

    @Test
    fun trash_keepsPages_untilPermanentDelete() = runBlocking {
        val id = repo.createNotebook("Pages")
        val page = repo.createPage(id)
        repo.deleteNotebook(id)
        // Content survives the soft delete…
        assertEquals(page, repo.getPage(page)?.id)
        // …and cascades on permanent deletion.
        repo.deleteNotebookPermanently(id)
        assertNull(repo.getPage(page))
    }

    @Test
    fun migration3to4_preservesNotebooks_unfiledAndUntrashed() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val factory = FrameworkSQLiteOpenHelperFactory()
        // File-backed so both helpers share one database file (in-memory
        // databases are per-connection and would never trigger onUpgrade).
        val file = java.io.File(ctx.cacheDir, "migration-3-4-test.db")
        if (file.exists()) file.delete()

        val v3Schema =
            "CREATE TABLE notebooks (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `type` TEXT NOT NULL DEFAULT 'NORMAL', " +
                "`coverId` TEXT NOT NULL DEFAULT 'TEAL', " +
                "`defaultTemplate` TEXT NOT NULL DEFAULT 'BLANK', " +
                "`isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                "`isArchived` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0)"

        // A v3 database: notebooks WITHOUT categoryId/deletedAt, no categories.
        val v3 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v3Schema)
                        db.execSQL(
                            "INSERT INTO notebooks (title, type) VALUES ('Legacy notes', 'NORMAL')"
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        v3.writableDatabase.close()
        v3.close()

        // Reopen the same file at v4: the real migration runs.
        val v4 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        AppDatabase.MIGRATION_3_4.migrate(db)
                    }
                })
                .build(),
        )
        val db4 = v4.writableDatabase

        val cols = mutableListOf<String>()
        db4.query("PRAGMA table_info(notebooks)").use { c ->
            while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
        }
        assertTrue(cols.contains("categoryId"))
        assertTrue(cols.contains("deletedAt"))

        db4.query("SELECT title, categoryId, deletedAt FROM notebooks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Legacy notes", c.getString(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
        db4.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'categories'"
        ).use { c ->
            assertTrue(c.moveToFirst())
        }
        v4.close()
        file.delete()
    }
}
