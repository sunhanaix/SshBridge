package com.sunbeat.sshclient.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY sort_order ASC, name ASC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT DISTINCT group_name FROM sessions WHERE group_name != '' ORDER BY group_name ASC")
    fun getAllGroups(): Flow<List<String>>

    @Query("SELECT * FROM sessions WHERE group_name = :group ORDER BY sort_order ASC, name ASC")
    fun getSessionsByGroup(group: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("UPDATE sessions SET group_name = :newName WHERE group_name = :oldName")
    suspend fun renameGroup(oldName: String, newName: String)

    @Query("UPDATE sessions SET group_name = '' WHERE group_name = :group")
    suspend fun clearGroup(group: String)

    // ── Batch / recursive group operations ─────────────────────────────

    @Query("DELETE FROM sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM sessions WHERE group_name = :group OR group_name LIKE :group || '/%'")
    suspend fun getSessionsInGroupTree(group: String): List<SessionEntity>

    @Query("UPDATE sessions SET group_name = '' WHERE group_name = :group OR group_name LIKE :group || '/%'")
    suspend fun clearGroupTree(group: String)
}
