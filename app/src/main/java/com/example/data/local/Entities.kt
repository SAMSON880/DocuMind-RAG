package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileName: String,
    val pageCount: Int,
    val chunkCount: Int,
    val totalChars: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"])]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentId: Long,
    val documentName: String,
    val pageNumber: Int,
    val chunkIndexInPage: Int,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    val embeddingCsv: String
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val role: String, // "user" or "assistant"
    val content: String,
    val sourcesSerialized: String = "",
    val isGrounded: Boolean = true,
    val modelUsed: String = "",
    val latencyMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)
