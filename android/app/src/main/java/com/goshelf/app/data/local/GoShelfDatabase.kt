package com.goshelf.app.data.local

import androidx.room.*

@Entity(tableName = "stars")
data class StarEntity(
    @PrimaryKey val id: String, // format: "book:123", "author:456", "series:slug"
    val type: String, // "book", "author", "series"
    val refId: String, // the actual ID or slug
    val name: String, // display name
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloaded_books")
data class DownloadedBookEntity(
    @PrimaryKey val bookId: Int,
    val title: String,
    val author: String,
    val outputDirUri: String,
    val downloadedAt: Long = System.currentTimeMillis(),
    val totalSize: Long = 0
)

@Dao
interface StarDao {
    @Query("SELECT * FROM stars ORDER BY createdAt DESC")
    suspend fun getAllStars(): List<StarEntity>

    @Query("SELECT * FROM stars WHERE type = :type ORDER BY name ASC")
    suspend fun getStarsByType(type: String): List<StarEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM stars WHERE id = :id)")
    suspend fun isStarred(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(star: StarEntity)

    @Query("DELETE FROM stars WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DownloadedBookDao {
    @Query("SELECT * FROM downloaded_books ORDER BY downloadedAt DESC")
    suspend fun getAllDownloads(): List<DownloadedBookEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_books WHERE bookId = :bookId)")
    suspend fun isDownloaded(bookId: Int): Boolean

    @Query("SELECT * FROM downloaded_books WHERE bookId = :bookId")
    suspend fun getDownload(bookId: Int): DownloadedBookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadedBookEntity)

    @Query("DELETE FROM downloaded_books WHERE bookId = :bookId")
    suspend fun delete(bookId: Int)
}

@Database(
    entities = [StarEntity::class, DownloadedBookEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GoShelfDatabase : RoomDatabase() {
    abstract fun starDao(): StarDao
    abstract fun downloadedBookDao(): DownloadedBookDao
}
