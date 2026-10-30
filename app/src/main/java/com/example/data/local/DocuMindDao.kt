package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocuMindDao {

    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun observeAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE fileName = :fileName LIMIT 1")
    suspend fun getDocumentByName(fileName: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Query("DELETE FROM documents WHERE id = :docId")
    suspend fun deleteDocumentById(docId: Long)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks ORDER BY documentId ASC, pageNumber ASC, chunkIndexInPage ASC")
    fun observeAllChunks(): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks ORDER BY documentId ASC, pageNumber ASC, chunkIndexInPage ASC")
    suspend fun getAllChunks(): List<ChunkEntity>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeChatHistory(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatHistory()
}
