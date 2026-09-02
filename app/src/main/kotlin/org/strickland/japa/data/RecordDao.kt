package org.strickland.japa.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(record: Record): Long

    @Update
    suspend fun update(record: Record)

    @Delete
    suspend fun delete(record: Record)

    /** Live list, kept sorted the same way the spinner shows it. */
    @Query("SELECT * FROM records ORDER BY name COLLATE NOCASE")
    fun getAll(): Flow<List<Record>>

    /** One-shot snapshot — used by the add/edit screen so paging is not disturbed mid-edit. */
    @Query("SELECT * FROM records ORDER BY name COLLATE NOCASE")
    suspend fun getAllOnce(): List<Record>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: Long): Record?
}
