package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversionTaskEntity::class,
        TaskCheckpointEntity::class,
        RedirectRecordEntity::class,
        IndexedDocumentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversionTaskDao(): ConversionTaskDao
    abstract fun searchIndexDao(): SearchIndexDao

    @Volatile
    var isFts5Available: Boolean = false
        internal set

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): AppDatabase {
            lateinit var dbRef: AppDatabase
            dbRef = Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                "syniji_zim_converter.db"
            )
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        dbRef.isFts5Available = ensureFts5Table(db)
                    }
                })
                .build()
            return dbRef
        }

        internal fun ensureFts5Table(db: SupportSQLiteDatabase): Boolean {
            return try {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts5 USING fts5(
                        taskId UNINDEXED,
                        title,
                        aliases,
                        categories,
                        originalPath,
                        content,
                        metadata,
                        tokenize='unicode61 remove_diacritics 2'
                    )
                    """.trimIndent()
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
