package com.sunbeat.sshclient.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = intPreferencesKey("font_size")
        val SCROLLBACK_LINES = intPreferencesKey("scrollback_lines")
        val TERMINAL_TYPE = stringPreferencesKey("terminal_type")
        val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val BELL_TYPE = stringPreferencesKey("bell_type")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val IDLE_TIMEOUT = intPreferencesKey("idle_timeout")
        val JSON_ASSET_VERSION = intPreferencesKey("json_asset_version")
        val KNOWN_GROUPS = stringPreferencesKey("known_groups")
    }

    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "dark" }
    val fontSize: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE] ?: 14 }
    val scrollbackLines: Flow<Int> = context.dataStore.data.map { it[SCROLLBACK_LINES] ?: 32000 }
    val terminalType: Flow<String> = context.dataStore.data.map { it[TERMINAL_TYPE] ?: "xterm-256color" }
    val cursorStyle: Flow<String> = context.dataStore.data.map { it[CURSOR_STYLE] ?: "block" }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[AUTO_RECONNECT] ?: false }
    val jsonAssetVersion: Flow<Int> = context.dataStore.data.map { it[JSON_ASSET_VERSION] ?: 0 }

    /** Known groups (user-created folders) — newline-separated, merged with DB groups. */
    val knownGroups: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KNOWN_GROUPS]?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    suspend fun addKnownGroup(group: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KNOWN_GROUPS] ?: ""
            val existing = current.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (group !in existing) {
                prefs[KNOWN_GROUPS] = (existing + group).joinToString("\n")
            }
        }
    }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[THEME] = value }
    }

    suspend fun setFontSize(value: Int) {
        context.dataStore.edit { it[FONT_SIZE] = value }
    }

    suspend fun setScrollbackLines(value: Int) {
        context.dataStore.edit { it[SCROLLBACK_LINES] = value }
    }

    suspend fun removeKnownGroup(group: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KNOWN_GROUPS] ?: ""
            val existing = current.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (group in existing) {
                prefs[KNOWN_GROUPS] = (existing - group).joinToString("\n")
            }
        }
    }

    /** Remove a group and all its descendant groups from the known set. */
    suspend fun removeKnownGroupTree(groupPath: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KNOWN_GROUPS] ?: ""
            if (current.isEmpty()) return@edit
            val existing = current.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val filtered = existing.filter { it != groupPath && !it.startsWith(groupPath + "/") }
            prefs[KNOWN_GROUPS] = filtered.joinToString("\n")
        }
    }

    suspend fun renameKnownGroup(oldName: String, newName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KNOWN_GROUPS] ?: ""
            val existing = current.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            if (oldName in existing) {
                existing.remove(oldName)
                existing.add(newName)
                prefs[KNOWN_GROUPS] = existing.joinToString("\n")
            }
        }
    }

    suspend fun setJsonAssetVersion(value: Int) {
        context.dataStore.edit { it[JSON_ASSET_VERSION] = value }
    }
}
