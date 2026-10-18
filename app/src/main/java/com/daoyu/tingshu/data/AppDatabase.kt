package com.daoyu.tingshu.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE ready = 1 ORDER BY CASE WHEN lastReadAt > 0 THEN lastReadAt ELSE addedAt END DESC")
    fun observeShelf(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    suspend fun all(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE sourceName = :name AND fileSize = :size AND ready = 1 LIMIT 1")
    suspend fun findSame(name: String, size: Long): Book?

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE books SET readChapter = :chapter, readOffset = :offset, progress = :progress, lastReadAt = :time WHERE id = :id")
    suspend fun saveProgress(id: Long, chapter: Int, offset: Int, progress: Float, time: Long = System.currentTimeMillis())

    @Query("UPDATE books SET dramaMode = :on WHERE id = :id")
    suspend fun setDramaMode(id: Long, on: Boolean)

    @Query("UPDATE books SET ttsEngine = :engine WHERE id = :id")
    suspend fun setTtsEngine(id: Long, engine: String?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM books WHERE ready = 0")
    suspend fun deleteUnfinished()
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun list(bookId: Long): List<Chapter>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Chapter>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapter, `offset`")
    fun observe(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(b: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: Long)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM tts_usage WHERE month = :month AND engine = :engine")
    suspend fun get(month: String, engine: String): TtsUsage?

    @Query("SELECT * FROM tts_usage ORDER BY month DESC, engine")
    fun observeAll(): Flow<List<TtsUsage>>

    @Query("SELECT * FROM tts_usage ORDER BY month DESC, engine")
    suspend fun observeAllOnce(): List<TtsUsage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(u: TtsUsage)
}

@Database(entities = [Book::class, Chapter::class, Bookmark::class, TtsUsage::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun usage(): UsageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "daoyu.db")
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // older rows only knew text length; estimate billed characters as 2 per char
                        db.execSQL("ALTER TABLE tts_usage ADD COLUMN billedChars INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("UPDATE tts_usage SET billedChars = chars * 2 WHERE engine = 'cosy'")
                    }
                }, object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE books ADD COLUMN dramaMode INTEGER NOT NULL DEFAULT 0")
                    }
                })
                .build()
    }
}
