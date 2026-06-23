package com.sunbeat.sshclient.domain.repository

import android.content.Context
import com.sunbeat.sshclient.data.local.SessionDao
import com.sunbeat.sshclient.data.local.toEntity
import com.sunbeat.sshclient.data.local.toSession
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.domain.parser.ScrtIniParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.io.File

class SessionRepository(private val sessionDao: SessionDao) {

    companion object {
        const val BUNDLED_JSON_VERSION = 4
    }

    fun getAllSessions(): Flow<List<Session>> =
        sessionDao.getAllSessions().map { list -> list.map { it.toSession() } }

    fun getAllGroups(): Flow<List<String>> = sessionDao.getAllGroups()

    fun getSessionsByGroup(group: String): Flow<List<Session>> =
        sessionDao.getSessionsByGroup(group).map { list -> list.map { it.toSession() } }

    suspend fun getById(id: Long): Session? = sessionDao.getById(id)?.toSession()

    suspend fun insert(session: Session): Long = sessionDao.insert(session.toEntity())

    suspend fun update(session: Session) = sessionDao.update(session.toEntity())

    suspend fun delete(session: Session) = sessionDao.delete(session.toEntity())

    suspend fun deleteById(id: Long) = sessionDao.deleteById(id)

    suspend fun deleteAll() = sessionDao.deleteAll()

    suspend fun count(): Int = sessionDao.count()

    /**
     * Imports SCRT .ini session files from a directory. Each .ini file is parsed
     * by [ScrtIniParser] and the resulting sessions are batch-inserted into the database.
     *
     * @param directoryPath Absolute path to the directory containing .ini files.
     * @param groupName Optional group name to assign to all imported sessions.
     * @return The number of sessions successfully imported.
     */
    suspend fun importFromDirectory(directoryPath: String, groupName: String = ""): Int {
        val dir = File(directoryPath)
        if (!dir.isDirectory) return 0

        val iniFiles = dir.listFiles { file -> file.extension.equals("ini", ignoreCase = true) }
            ?: return 0

        val sessions = iniFiles.mapNotNull { file ->
            val lines = file.readLines()
            val entries = ScrtIniParser.parse(lines)
            if (entries.isEmpty()) null
            else ScrtIniParser.parseSession(entries, file.nameWithoutExtension, groupName)
        }

        if (sessions.isNotEmpty()) {
            sessionDao.insertAll(sessions.map { it.toEntity() })
        }
        return sessions.size
    }

    /**
     * Imports sessions from a bundled JSON asset file. The JSON must be an array
     * of objects with fields matching the SCRT export format.
     *
     * @param context Android context used to access assets.
     * @param fileName Asset file name (default [sessions_export.json]).
     * @return The number of sessions successfully imported.
     */
    suspend fun importFromJsonAssets(context: Context, fileName: String = "sessions_export.json"): Int {
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(jsonString)

        // Dedup by hostname:port:username — keep the entry with a password if duplicate
        val seen = linkedMapOf<String, Session>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val hostname = obj.optString("hostname", "")
            val port = obj.optInt("port", 22)
            val username = obj.optString("username", "")
            val key = "$hostname:$port:$username"

            val session = Session(
                name = obj.optString("session", ""),
                groupName = obj.optString("folder", ""),
                hostname = hostname,
                port = port,
                username = username,
                protocol = obj.optString("protocol", "SSH2"),
                plainPassword = if (obj.optBoolean("has_saved_password", false))
                    if (obj.has("password")) obj.getString("password") else null else null,
                jumpHost = obj.optString("firewall", "")
                    .let { if (it == "None" || it.isEmpty()) null else it },
                sourceFile = fileName,
            )

            // Keep the entry with a password if a duplicate exists
            val existing = seen[key]
            if (existing == null) {
                seen[key] = session
            } else if (existing.plainPassword.isNullOrEmpty() && !session.plainPassword.isNullOrEmpty()) {
                seen[key] = session // Replace with the one that has a password
            }
        }

        val sessions = seen.values.toList()
        if (sessions.isNotEmpty()) {
            sessionDao.insertAll(sessions.map { it.toEntity() })
        }
        return sessions.size
    }

    suspend fun renameGroup(oldName: String, newName: String) {
        sessionDao.renameGroup(oldName, newName)
    }

    suspend fun clearGroup(group: String) {
        sessionDao.clearGroup(group)
    }

    suspend fun deleteByIds(ids: List<Long>) {
        sessionDao.deleteByIds(ids)
    }

    suspend fun getSessionsInGroupTree(group: String): List<Session> =
        sessionDao.getSessionsInGroupTree(group).map { it.toSession() }

    suspend fun clearGroupTree(group: String) {
        sessionDao.clearGroupTree(group)
    }

    suspend fun updateAll(sessions: List<Session>) {
        sessionDao.insertAll(sessions.map { it.toEntity() })
    }
}
