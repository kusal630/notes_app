package com.vellum.notes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotebookEntity::class, PageEntity::class, CategoryEntity::class, BookHighlightEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun categoryDao(): CategoryDao
    abstract fun highlightDao(): HighlightDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vellum.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }

        /** v1 → v2: notebooks gain a note-type column (NORMAL/CLASSROOM). */
        val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE notebooks ADD COLUMN type TEXT NOT NULL DEFAULT 'NORMAL'")
        }

        /**
         * v2 → v3: covers + paper templates + PDF-backed pages. All additive with
         * defaults; no destructive migration.
         */
        val MIGRATION_2_3 = androidx.room.migration.Migration(2, 3) { db ->
            db.execSQL("ALTER TABLE notebooks ADD COLUMN coverId TEXT NOT NULL DEFAULT 'TEAL'")
            db.execSQL("ALTER TABLE notebooks ADD COLUMN defaultTemplate TEXT NOT NULL DEFAULT 'BLANK'")
            db.execSQL("ALTER TABLE pages ADD COLUMN templateId TEXT NOT NULL DEFAULT 'BLANK'")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdfPageIndex INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE pages ADD COLUMN pdfBackgroundPath TEXT NOT NULL DEFAULT ''")
        }

        /**
         * v3 → v4: Noteshelf organization. Notebooks gain an optional category and a
         * trash flag; a new categories table holds user-defined shelves. All
         * additive; existing notebooks land in Unfiled, nothing is trashed.
         */
        val MIGRATION_3_4 = androidx.room.migration.Migration(3, 4) { db ->
            db.execSQL("ALTER TABLE notebooks ADD COLUMN categoryId INTEGER")
            db.execSQL("ALTER TABLE notebooks ADD COLUMN deletedAt INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS categories " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
        }

        /**
         * v4 → v5: read-mode highlights. A new table with cascading foreign keys;
         * no existing data is touched.
         */
        val MIGRATION_4_5 = androidx.room.migration.Migration(4, 5) { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS book_highlights (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`notebookId` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, " +
                    "`pointsJson` TEXT NOT NULL DEFAULT '[]', " +
                    "`colorArgb` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_highlights_notebookId` ON `book_highlights` (`notebookId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_highlights_pageId` ON `book_highlights` (`pageId`)")
        }
    }
}