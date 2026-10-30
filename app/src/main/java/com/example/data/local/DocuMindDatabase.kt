package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, ChunkEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DocuMindDatabase : RoomDatabase() {
    abstract fun dao(): DocuMindDao

    companion object {
        @Volatile
        private var INSTANCE: DocuMindDatabase? = null

        fun getInstance(context: Context): DocuMindDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DocuMindDatabase::class.java,
                    "documind_local_rag.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
