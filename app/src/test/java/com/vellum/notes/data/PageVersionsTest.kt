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

/** Page version history: snapshots, dedup, restore, prune, cascade, migration. */
@RunWith(RobolectricTestRunner::class)
class PageVersionsTest {

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

    private fun content(text: String) = PageContent(
        textObjects = listOf(
            TextObject(id = 1L, x = 0f, y = 0f, width = 90f, height = 20f, text = text)
        )
    )

    @Test
    fun saveVersion_snapshotsAndSkipsDuplicates() = runBlocking {
        val nb = repo.createNotebook("V")
        val page = repo.createPage(nb)
        repo.savePageContent(page, content("one"))

        repo.saveVersion(page)
        repo.saveVersion(page) // identical: skipped
        assertEquals(1, repo.versionsForPage(page).first().size)

        repo.savePageContent(page, content("two"))
        repo.saveVersion(page)
        assertEquals(2, repo.versionsForPage(page).first().size)
    }

    @Test
    fun restoreVersion_replacesContentAndStaysReversible() = runBlocking {
        val nb = repo.createNotebook("V")
        val page = repo.createPage(nb)
        repo.savePageContent(page, content("original"))
        repo.saveVersion(page) // v1 = "original"
        // Unsnapshotted edits (autosaved, no version yet).
        repo.savePageContent(page, content("edited"))

        val versions = repo.versionsForPage(page).first()
        assertEquals(1, versions.size)
        repo.restoreVersion(versions[0].id)

        assertEquals("original", repo.loadPageContent(page)?.textObjects?.first()?.text)
        // The pre-restore "edited" state was snapshotted: still reversible.
        val after = repo.versionsForPage(page).first()
        assertEquals(2, after.size)
        repo.restoreVersion(after[0].id)
        assertEquals("edited", repo.loadPageContent(page)?.textObjects?.first()?.text)
    }

    @Test
    fun versions_prunedToMax() = runBlocking {
        val nb = repo.createNotebook("V")
        val page = repo.createPage(nb)
        repeat(NotesRepository.MAX_VERSIONS_PER_PAGE + 5) { i ->
            repo.savePageContent(page, content("v$i"))
            repo.saveVersion(page)
        }
        assertEquals(
            NotesRepository.MAX_VERSIONS_PER_PAGE,
            repo.versionsForPage(page).first().size,
        )
    }

    @Test
    fun versions_cascadeWithPage() = runBlocking {
        val nb = repo.createNotebook("V")
        val page = repo.createPage(nb)
        repo.savePageContent(page, content("x"))
        repo.saveVersion(page)
        assertEquals(1, repo.versionsForPage(page).first().size)
        repo.deletePage(page)
        assertTrue(repo.versionsForPage(page).first().isEmpty())
    }

    @Test
    fun deleteVersion_removesOneSnapshot() = runBlocking {
        val nb = repo.createNotebook("V")
        val page = repo.createPage(nb)
        repo.savePageContent(page, content("a"))
        repo.saveVersion(page)
        repo.savePageContent(page, content("b"))
        repo.saveVersion(page)
        val versions = repo.versionsForPage(page).first()
        repo.deleteVersion(versions[0].id)
        assertEquals(1, repo.versionsForPage(page).first().size)
    }

    @Test
    fun migration6to7_createsVersionsTable() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val file = java.io.File(ctx.cacheDir, "migration-6-7-test.db")
        if (file.exists()) file.delete()

        // v6 seed: every table Room validates (FKs + indices included).
        val create = listOf(
            "CREATE TABLE notebooks (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `type` TEXT NOT NULL DEFAULT 'NORMAL', " +
                "`coverId` TEXT NOT NULL DEFAULT 'TEAL', " +
                "`defaultTemplate` TEXT NOT NULL DEFAULT 'BLANK', " +
                "`isFavorite` INTEGER NOT NULL DEFAULT 0, " +
                "`isArchived` INTEGER NOT NULL DEFAULT 0, " +
                "`categoryId` INTEGER, `deletedAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0)",
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
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX `index_pages_notebookId` ON `pages` (`notebookId`)",
            "CREATE TABLE categories (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE book_highlights (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`notebookId` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, " +
                "`pointsJson` TEXT NOT NULL DEFAULT '[]', " +
                "`colorArgb` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX `index_book_highlights_notebookId` ON `book_highlights` (`notebookId`)",
            "CREATE INDEX `index_book_highlights_pageId` ON `book_highlights` (`pageId`)",
            "CREATE TABLE tags (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)",
            "CREATE TABLE notebook_tags (`notebookId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`notebookId`, `tagId`), " +
                "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX `index_notebook_tags_tagId` ON `notebook_tags` (`tagId`)",
            "CREATE VIRTUAL TABLE page_search USING FTS4(" +
                "`pageId` INTEGER NOT NULL, `notebookId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `body` TEXT NOT NULL)",
            "INSERT INTO notebooks (title) VALUES ('Legacy')",
            "INSERT INTO pages (notebookId, title) VALUES (1, 'Legacy page')",
        )
        val v6 = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        create.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        v6.writableDatabase.close()
        v6.close()

        // Real Room open: migration 6→7 runs and the full schema validates.
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, file.absolutePath)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val nb = RoomNotesRepository(room).createNotebook("After migration")
            val page = RoomNotesRepository(room).createPage(nb)
            RoomNotesRepository(room).savePageContent(page, content("v1"))
            RoomNotesRepository(room).saveVersion(page)
            assertEquals(1, RoomNotesRepository(room).versionsForPage(page).first().size)
        }
        room.close()
        file.delete()
    }
}
