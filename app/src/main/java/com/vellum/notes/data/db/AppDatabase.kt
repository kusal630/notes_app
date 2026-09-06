package com.vellum.notes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotebookEntity::class, PageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    }
}