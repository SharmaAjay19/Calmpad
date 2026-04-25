package com.calmpad.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── Entities ──

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val sectionId: String,
    val title: String,
    val content: String,
    val updatedAt: Long,
    val versions: String = "[]" // JSON array of NoteVersion
)

@Entity(tableName = "sections")
data class SectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val noteOrder: String = "[]", // JSON array of note IDs
    val sortIndex: Int = 0
)

@Serializable
data class NoteVersion(
    val content: String,
    val time: Long
)

// ── Type Converters ──

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseVersions(raw: String): List<NoteVersion> =
        try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }

    fun versionsToString(versions: List<NoteVersion>): String =
        json.encodeToString(versions)

    fun parseNoteOrder(raw: String): List<String> =
        try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }

    fun noteOrderToString(order: List<String>): String =
        json.encodeToString(order)
}

// ── DAOs ──

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE sectionId = :sectionId")
    fun getNotesBySection(sectionId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("DELETE FROM notes WHERE sectionId = :sectionId")
    suspend fun deleteNotesBySection(sectionId: String)

    @Query("SELECT * FROM notes")
    suspend fun getAllNotes(): List<NoteEntity>
}

@Dao
interface SectionDao {
    @Query("SELECT * FROM sections ORDER BY sortIndex")
    fun getAllSections(): Flow<List<SectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSection(section: SectionEntity)

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun deleteSection(id: String)

    @Query("SELECT * FROM sections ORDER BY sortIndex")
    suspend fun getAllSectionsList(): List<SectionEntity>

    @Query("SELECT COUNT(*) FROM sections")
    suspend fun getSectionCount(): Int
}

// ── Database ──

@Database(entities = [NoteEntity::class, SectionEntity::class], version = 1, exportSchema = false)
abstract class CalmPadDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun sectionDao(): SectionDao
}
