package com.vellum.notes.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.vellum.notes.data.db.AppDatabase
import com.vellum.notes.model.BookHighlight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Read-mode highlights: CRUD, review join, cascades, v4 → v5 migration. */
@RunWith(RobolectricTestRunner::class)
class HighlightsTest {

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

    private fun stroke() = listOf(0.1f, 0.2f, 0.3f, 0.2f, 0.5f, 0.25f)

    @Test
    fun highlights_crudAndReviewJoin() = runBlocking {
        val nb = repo.createNotebook("Biology")
        val page = repo.createPage(nb, title = "Cell")
        val id = repo.addHighlight(
            BookHighlight(notebookId = nb, pageId = page, points = stroke())
        )

        val perBook = repo.highlightsForNotebook(nb).first()
        assertEquals(1, perBook.size)
        assertEquals(stroke(), perBook[0].points)
        assertEquals(page, perBook[0].pageId)

        val review = repo.allHighlights.first()
        assertEquals(1, review.size)
        assertEquals("Biology", review[0].notebookTitle)
        assertEquals("Cell", review[0].pageTitle)

        repo.deleteHighlight(id)
        assertTrue(repo.highlightsForNotebook(nb).first().isEmpty())
        assertTrue(repo.allHighlights.first().isEmpty())
    }

    @Test
    fun highlights_pointsRoundTripExactly() = runBlocking {
        val nb = repo.createNotebook("Book")
        val page = repo.createPage(nb)
        val pts = (0 until 20).map { it / 19f }
        repo.addHighlight(BookHighlight(notebookId = nb, pageId = page, points = pts))
        assertEquals(pts, repo.highlightsForNotebook(nb).first()[0].points)
    }

    @Test
    fun highlights_cascadeWithPageAndNotebook() = runBlocking {
        val nb = repo.createNotebook("Temp book")
        val page = repo.createPage(nb)
        repo.addHighlight(BookHighlight(notebookId = nb, pageId = page, points = stroke()))

        repo.deletePage(page)
        assertTrue(repo.highlightsForNotebook(nb).first().isEmpty())

        val page2 = repo.createPage(nb)
        repo.addHighlight(BookHighlight(notebookId = nb, pageId = page2, points = stroke()))
        repo.deleteNotebookPermanently(nb)
        assertTrue(repo.allHighlights.first().isEmpty())
    }

    @Test
    fun migration4to5_createsHighlightsTable() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val file = java.io.File(ctx.cacheDir, "migration-4-5-test.db")
        if (file.exists()) file.delete()

        val v4 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE notebooks (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`title` TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO notebooks (title) VALUES ('Old book')"
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
        v4.writableDatabase.close()
        v4.close()

        val v5 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        AppDatabase.MIGRATION_4_5.migrate(db)
                    }
                })
                .build(),
        )
        val db5 = v5.writableDatabase
        db5.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'book_highlights'"
        ).use { c ->
            assertTrue(c.moveToFirst())
        }
        // Legacy data survives the migration.
        db5.query("SELECT title FROM notebooks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Old book", c.getString(0))
        }
        v5.close()
        file.delete()
    }
}
