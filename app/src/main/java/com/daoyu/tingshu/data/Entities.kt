package com.daoyu.tingshu.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val format: String,                 // txt / epub
    val sourceName: String,             // original file name
    val fileSize: Long,
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val totalChars: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val readChapter: Int = 0,
    val readOffset: Int = 0,            // char offset inside chapter body
    val progress: Float = 0f,           // 0..1 over the whole book
    val ttsEngine: String? = null,      // per-book engine override, null = follow default
    val ready: Boolean = false,         // parsing finished
    val dramaMode: Boolean = false,     // listen with multiple character voices
)

@Entity(tableName = "chapters", primaryKeys = ["bookId", "idx"])
data class Chapter(
    val bookId: Long,
    val idx: Int,
    val title: String,
    val charCount: Int,
    val startChar: Long,                // cumulative chars before this chapter
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    val offset: Int,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tts_usage", primaryKeys = ["month", "engine"])
data class TtsUsage(
    val month: String,                  // yyyy-MM
    val engine: String,
    val chars: Long,
    val requests: Long,
    val audioTokens: Long = 0,          // Gemini bills by audio tokens
    val billedChars: Long = 0,          // Aliyun billing characters (汉字 = 2)
)
