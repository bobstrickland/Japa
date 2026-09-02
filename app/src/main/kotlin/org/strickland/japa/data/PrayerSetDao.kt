package org.strickland.japa.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrayerSetDao {

    // ── Sets ──────────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertSet(set: PrayerSet): Long

    @Update
    suspend fun updateSet(set: PrayerSet)

    /** Memberships cascade away with the set; the prayers themselves are untouched. */
    @Delete
    suspend fun deleteSet(set: PrayerSet)

    @Query("SELECT * FROM prayer_sets ORDER BY name COLLATE NOCASE")
    fun observeSets(): Flow<List<PrayerSet>>

    @Query("SELECT * FROM prayer_sets ORDER BY name COLLATE NOCASE")
    suspend fun getSetsOnce(): List<PrayerSet>

    @Query("SELECT * FROM prayer_sets WHERE id = :setId")
    suspend fun getSet(setId: Long): PrayerSet?

    // ── Membership ────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT r.* FROM records r
        INNER JOIN set_members m ON m.recordId = r.id
        WHERE m.setId = :setId
        ORDER BY m.position
        """
    )
    fun observeMembers(setId: Long): Flow<List<Record>>

    @Query(
        """
        SELECT r.* FROM records r
        INNER JOIN set_members m ON m.recordId = r.id
        WHERE m.setId = :setId
        ORDER BY m.position
        """
    )
    suspend fun getMembersOnce(setId: Long): List<Record>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: SetMember)

    @Query("DELETE FROM set_members WHERE setId = :setId AND recordId = :recordId")
    suspend fun removeMember(setId: Long, recordId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM set_members WHERE setId = :setId")
    suspend fun nextPosition(setId: Long): Int

    @Query("UPDATE set_members SET position = :position WHERE setId = :setId AND recordId = :recordId")
    suspend fun updatePosition(setId: Long, recordId: Long, position: Int)

    /** Appends [recordId] to the end of [setId]; a prayer already in the set keeps its place. */
    @Transaction
    suspend fun addToSet(setId: Long, recordId: Long) {
        insertMember(SetMember(setId, recordId, nextPosition(setId)))
    }

    /**
     * Rewrites the whole set's positions from [orderedRecordIds]. Renumbering everything keeps
     * positions dense and gap-free, which reordering by swapping neighbours does not.
     */
    @Transaction
    suspend fun reorder(setId: Long, orderedRecordIds: List<Long>) {
        orderedRecordIds.forEachIndexed { index, recordId ->
            updatePosition(setId, recordId, index)
        }
    }
}
