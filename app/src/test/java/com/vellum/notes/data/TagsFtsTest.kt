package com.vellum.notes.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.vellum.notes.data.db.AppDatabase
import com.vellum.notes.model.PageContent
import com.vellum.notes.model.TextObject
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

/** Tags + full-text search: CRUD, assignment, FTS sync/search, v5 → v6 migration. */
@RunWith(RobolectricTestRunner::class)
class TagsFtsTest {

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
    fun tags_crudAssignmentAndCounts() = runBlocking {
        val physics = repo.createTag("physics")
        // Case-insensitive reuse: no duplicates.
        assertEquals(physics, repo.createTag("Physics"))
        val math = repo.createTag("math")

        val a = repo.createNotebook("A")
        val b = repo.createNotebook("B")
        repo.setNotebookTags(a, setOf(physics, math))
        repo.setNotebookTags(b, setOf(physics))

        assertEquals(setOf("math", "physics"), repo.tagsForNotebook(a).map { it.name }.toSet())
        val tags = repo.allTags.first()
        assertEquals(2, tags.first { it.id == physics }.notebookCount)
        assertEquals(1, tags.first { it.id == math }.notebookCount)

        repo.setNotebookTags(a, setOf(math))
        assertEquals(listOf("math"), repo.tagsForNotebook(a).map { it.name })

        repo.renameTag(math, "algebra")
        assertTrue(repo.allTags.first().any { it.name == "algebra" })

        repo.deleteTag(physics)
        assertEquals(1, repo.allTags.first().size)
        assertEquals(2, repo.notebooks.first().size)
    }

    @Test
    fun tags_cascadeWithNotebook() = runBlocking {
        val t = repo.createTag("temp")
        val nb = repo.createNotebook("Temp")
        repo.setNotebookTags(nb, setOf(t))
        repo.deleteNotebookPermanently(nb)
        assertEquals(0, repo.allTags.first().first { it.id == t }.notebookCount)
    }

    @Test
    fun fts_indexesTitlesAndTextAndSearches() = runBlocking {
        val nb = repo.createNotebook("Biology notes")
        val page = repo.createPage(nb, title = "Cell structure")
        repo.savePageContent(
            page,
            PageContent(
                textObjects = listOf(
                    TextObject(id = 1L, x = 0f, y = 0f, width = 90f, height = 20f, text = "Mitochondria produce energy")
                )
            )
        )

        // Title match (page title + notebook title both indexed).
        assertTrue(repo.searchPageTexts("structure").contains(nb))
        // Body match.
        assertTrue(repo.searchPageTexts("mitochondria").contains(nb))
        // Multi-word AND semantics.
        assertTrue(repo.searchPageTexts("mitochondria energy").contains(nb))
        assertTrue(repo.searchPageTexts("mitochondria absent").isEmpty())
        // No false positives on other notebooks.
        val other = repo.createNotebook("Chemistry")
        repo.createPage(other, title = "Atoms")
        assertTrue(!repo.searchPageTexts("mitochondria").contains(other))
    }

    @Test
    fun fts_querySanitization_neverBreaksSyntax() {
        assertEquals("", SearchIndex.sanitizeQuery("!!!"))
        assertEquals("", SearchIndex.sanitizeQuery(""))
        assertEquals("\"hello\"", SearchIndex.sanitizeQuery("hello!"))
        // Quoted tokens neutralize operators: OR becomes a harmless literal.
        assertEquals("\"a\" \"OR\" \"b\"", SearchIndex.sanitizeQuery("a) OR (b"))
    }

    @Test
    fun fts_bodyBuilder_coversTextTranscriptSummary() {
        val body = SearchIndex.bodyFor(
            PageContent(
                textObjects = listOf(
                    TextObject(id = 1L, x = 0f, y = 0f, width = 90f, height = 20f, text = "typed words")
                ),
                transcript = listOf(
                    com.vellum.notes.model.TranscriptSegment(id = 0L, startMs = 0L, text = "spoken words")
                ),
                summary = "condensed words",
            )
        )
        assertTrue(body.contains("typed words"))
        assertTrue(body.contains("spoken words"))
        assertTrue(body.contains("condensed words"))
    }

    @Test
    fun fts_syncsOnRenameDuplicateAndDelete() = runBlocking {
        val nb = repo.createNotebook("Sync")
        val page = repo.createPage(nb, title = "Original title")
        assertTrue(repo.searchPageTexts("original").contains(nb))

        repo.renamePage(page, "Renamed title")
        assertTrue(repo.searchPageTexts("renamed").contains(nb))
        assertTrue(repo.searchPageTexts("original").isEmpty())

        val copy = repo.duplicatePage(page)
        assertTrue(copy > 0L)
        assertTrue(repo.searchPageTexts("renamed").contains(nb))

        repo.deletePage(page)
        repo.deletePage(copy)
        assertTrue(repo.searchPageTexts("renamed").isEmpty())
    }

    @Test
    fun fts_trashAndPermanentDelete_hideAndDropIndex() = runBlocking {
        val nb = repo.createNotebook("Ephemeral")
        val page = repo.createPage(nb, title = "Uniquetermxyz")
        assertTrue(repo.searchPageTexts("uniquetermxyz").contains(nb))

        // Trash hides the notebook from Home, but the index row is only
        // dropped on permanent deletion (restore must keep working).
        repo.deleteNotebook(nb)
        repo.deleteNotebookPermanently(nb)
        assertTrue(repo.searchPageTexts("uniquetermxyz").isEmpty())
    }

    @Test
    fun migration5to6_createsTablesAndBackfillsTitles() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val file = java.io.File(ctx.cacheDir, "migration-5-6-test.db")
        if (file.exists()) file.delete()

        // Full v5 schema (every column from migrations 1-4): Room validates the
        // whole database after migration, so a minimal seed cannot be used.
        val v5 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE notebooks (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`title` TEXT NOT NULL, `type` TEXT NOT NULL DEFAULT 'NORMAL', " +
                                "`coverId` TEXT NOT NULL DEFAULT 'TEAL', " +
                                "`defaultTemplate` TEXT NOT NULL DEFAULT 'BLANK', " +
                                "`isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                                "`isArchived` INTEGER NOT NULL DEFAULT 0, " +
                                "`categoryId` INTEGER, `deletedAt` INTEGER, " +
                                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                "`updatedAt` INTEGER NOT NULL DEFAULT 0)"
                        )
                        db.execSQL(
                            "CREATE TABLE pages (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`notebookId` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                                "`order` INTEGER NOT NULL DEFAULT 0, " +
                                "`backgroundJson` TEXT NOT NULL DEFAULT '{}', " +
                                "`templateId` TEXT NOT NULL DEFAULT 'BLANK', " +
                                "`pdfPageIndex` INTEGER NOT NULL DEFAULT -1, " +
                                "`pdfBackgroundPath` TEXT NOT NULL DEFAULT '', " +
                                "`contentJson` TEXT NOT NULL DEFAULT '', " +
                                "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                                "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) " +
                                "ON UPDATE NO ACTION ON DELETE CASCADE)"
                        )
                        db.execSQL("CREATE INDEX `index_pages_notebookId` ON `pages` (`notebookId`)")
                        db.execSQL(
                            "CREATE TABLE categories (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL DEFAULT 0)"
                        )
                        db.execSQL(
                            "CREATE TABLE book_highlights (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`notebookId` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, " +
                                "`pointsJson` TEXT NOT NULL DEFAULT '[]', " +
                                "`colorArgb` INTEGER NOT NULL DEFAULT 0, " +
                                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) " +
                                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                                "FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                                "ON UPDATE NO ACTION ON DELETE CASCADE)"
                        )
                        db.execSQL("CREATE INDEX `index_book_highlights_notebookId` ON `book_highlights` (`notebookId`)")
                        db.execSQL("CREATE INDEX `index_book_highlights_pageId` ON `book_highlights` (`pageId`)")
                        db.execSQL("INSERT INTO notebooks (title) VALUES ('Legacy')")
                        db.execSQL("INSERT INTO pages (notebookId, title) VALUES (1, 'Legacy page')")
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        v5.writableDatabase.close()
        v5.close()

        // Open the same file with the real Room database: migration 5→6 runs
        // and Room validates the resulting schema (catches FTS DDL drift).
        // The 6→7 step rides along (current version is 7).
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, file.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
        // Backfilled title is searchable immediately.
        val dao = room.pageSearchDao()
        runBlocking {
            assertTrue(dao.searchNotebookIds("\"Legacy\"").contains(1L))
        }
        room.close()
        file.delete()
    }
}
