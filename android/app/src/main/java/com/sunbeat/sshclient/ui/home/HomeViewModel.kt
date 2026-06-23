package com.sunbeat.sshclient.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sunbeat.sshclient.data.import.ConfigImporter
import com.sunbeat.sshclient.data.preferences.AppPreferences
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.domain.repository.SessionRepository
import com.sunbeat.sshclient.domain.ssh.ConnPool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class HomeUiState(
    val sessions: List<Session> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String = "",
    val searchQuery: String = "",
    val activeConnections: Set<Long> = emptySet(),
    val statusMessage: String? = null,
    // Multi-select batch mode
    val selectionMode: Boolean = false,
    val selectedSessionIds: Set<Long> = emptySet(),
    val selectedGroupPaths: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: SessionRepository,
    private val connPool: ConnPool,
    private val preferences: AppPreferences,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val groupFilter = MutableStateFlow("")

    init {
        // Load sessions reactively — empty group = all sessions
        viewModelScope.launch {
            groupFilter.flatMapLatest { group ->
                if (group.isEmpty()) repository.getAllSessions()
                else repository.getSessionsByGroup(group)
            }.collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions)
            }
        }

        // Load groups — merge DB-derived groups with user-created known groups
        viewModelScope.launch {
            repository.getAllGroups().combine(preferences.knownGroups) { dbGroups, known ->
                (dbGroups + known).sorted()
            }.collect { merged ->
                _uiState.value = _uiState.value.copy(groups = merged)
            }
        }

        // Track active connections
        viewModelScope.launch {
            connPool.activeIds.collect { ids ->
                _uiState.value = _uiState.value.copy(activeConnections = ids)
            }
        }

        // Import bundled SCRT JSON with version tracking
        viewModelScope.launch {
            val storedVersion = preferences.jsonAssetVersion.first()
            if (storedVersion < SessionRepository.BUNDLED_JSON_VERSION) {
                repository.deleteAll()
                repository.importFromJsonAssets(appContext)
                preferences.setJsonAssetVersion(SessionRepository.BUNDLED_JSON_VERSION)
            }
        }
    }

    fun selectGroup(group: String) {
        _uiState.value = _uiState.value.copy(selectedGroup = group)
        groupFilter.value = group
    }

    fun onSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun importFromDirectory(path: String) {
        viewModelScope.launch {
            repository.importFromDirectory(path)
        }
    }

    fun importBundledJson() {
        viewModelScope.launch {
            repository.importFromJsonAssets(appContext)
        }
    }

    fun deleteAllAndReimport() {
        viewModelScope.launch {
            repository.deleteAll()
            repository.importFromJsonAssets(appContext)
            preferences.setJsonAssetVersion(SessionRepository.BUNDLED_JSON_VERSION)
        }
    }

    fun addSession(session: Session) {
        viewModelScope.launch {
            val s = applyCurrentGroup(session)
            repository.insert(s)
            if (s.groupName.isNotEmpty()) {
                preferences.addKnownGroup(s.groupName)
            }
        }
    }

    fun updateSession(session: Session) {
        viewModelScope.launch {
            repository.update(session)
            if (session.groupName.isNotEmpty()) {
                preferences.addKnownGroup(session.groupName)
            }
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            repository.delete(session)
        }
    }

    fun importFromBase64(base64: String) {
        // Route sync server URLs to the network fetch path
        if (ConfigImporter.isSyncUrl(base64)) {
            importFromSyncUrl(base64)
            return
        }
        val result = ConfigImporter.parse(base64)
        if (result.error != null) {
            _uiState.value = _uiState.value.copy(statusMessage = "Import failed: ${result.error}")
            return
        }
        viewModelScope.launch {
            var count = 0
            for (session in result.sessions) {
                val s = applyCurrentGroup(session)
                repository.insert(s)
                if (s.groupName.isNotEmpty()) {
                    preferences.addKnownGroup(s.groupName)
                }
                count++
            }
            _uiState.value = _uiState.value.copy(
                statusMessage = "Imported $count session${if (count != 1) "s" else ""}"
            )
        }
    }

    fun importFromSyncUrl(syncUrl: String) {
        viewModelScope.launch {
            val result = ConfigImporter.fetchFromSyncUrl(syncUrl)
            if (result.error != null) {
                _uiState.value = _uiState.value.copy(statusMessage = "Sync failed: ${result.error}")
                return@launch
            }
            var count = 0
            for (session in result.sessions) {
                val s = applyCurrentGroup(session)
                repository.insert(s)
                if (s.groupName.isNotEmpty()) {
                    preferences.addKnownGroup(s.groupName)
                }
                count++
            }
            _uiState.value = _uiState.value.copy(
                statusMessage = "Synced $count session${if (count != 1) "s" else ""}"
            )
        }
    }

    fun createGroup(groupName: String) {
        _uiState.value = _uiState.value.copy(selectedGroup = groupName)
        groupFilter.value = groupName
        viewModelScope.launch {
            preferences.addKnownGroup(groupName)
        }
    }

    fun navigateUp() {
        val current = _uiState.value.selectedGroup
        if (current.isEmpty()) return
        val lastSlash = current.lastIndexOf('/')
        if (lastSlash > 0) {
            selectGroup(current.substring(0, lastSlash))
        } else {
            selectGroup("")
        }
    }

    fun navigateToGroup(group: String) {
        selectGroup(group)
    }

    fun renameGroup(oldName: String, newName: String) {
        viewModelScope.launch {
            repository.renameGroup(oldName, newName)
            preferences.renameKnownGroup(oldName, newName)
            if (_uiState.value.selectedGroup == oldName) {
                selectGroup(newName)
            }
        }
    }

    fun deleteGroup(groupName: String) {
        viewModelScope.launch {
            repository.clearGroup(groupName)
            preferences.removeKnownGroup(groupName)
            if (_uiState.value.selectedGroup == groupName) {
                selectGroup("")
            }
        }
    }

    // ── Multi-select batch mode ──────────────────────────────────────

    fun enterSelectionMode() {
        _uiState.value = _uiState.value.copy(
            selectionMode = true,
            selectedSessionIds = emptySet(),
            selectedGroupPaths = emptySet(),
        )
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            selectionMode = false,
            selectedSessionIds = emptySet(),
            selectedGroupPaths = emptySet(),
        )
    }

    fun toggleSessionSelection(id: Long) {
        val current = _uiState.value
        val ids = current.selectedSessionIds.toMutableSet()
        if (id in ids) ids.remove(id) else ids.add(id)
        _uiState.value = current.copy(selectedSessionIds = ids)
    }

    fun toggleGroupSelection(path: String) {
        val current = _uiState.value
        val paths = current.selectedGroupPaths.toMutableSet()
        if (path in paths) paths.remove(path) else paths.add(path)
        _uiState.value = current.copy(selectedGroupPaths = paths)
    }

    fun selectAllFiltered() {
        val current = _uiState.value
        val allSessionIds = current.sessions.map { it.id }.toSet()
        val currentGroups = if (current.selectedGroup.isEmpty()) {
            current.groups.toSet()
        } else {
            current.groups.filter { it.startsWith(current.selectedGroup + "/") || it == current.selectedGroup }.toSet()
        }
        _uiState.value = current.copy(
            selectedSessionIds = allSessionIds,
            selectedGroupPaths = currentGroups,
        )
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(
            selectedSessionIds = emptySet(),
            selectedGroupPaths = emptySet(),
        )
    }

    fun batchDelete() {
        viewModelScope.launch {
            val state = _uiState.value
            val idsToDelete = state.selectedSessionIds.toMutableSet()

            // Collect all session IDs under selected group trees
            for (groupPath in state.selectedGroupPaths) {
                val treeSessions = repository.getSessionsInGroupTree(groupPath)
                idsToDelete.addAll(treeSessions.map { it.id })
                // Remove group and children from known_groups
                preferences.removeKnownGroupTree(groupPath)
            }

            if (idsToDelete.isNotEmpty()) {
                repository.deleteByIds(idsToDelete.toList())
            }

            _uiState.value = state.copy(
                statusMessage = "Deleted ${idsToDelete.size} session(s)",
                selectionMode = false,
                selectedSessionIds = emptySet(),
                selectedGroupPaths = emptySet(),
            )
        }
    }

    fun batchMove(targetGroup: String) {
        viewModelScope.launch {
            val state = _uiState.value

            // Move individually selected sessions
            if (state.selectedSessionIds.isNotEmpty()) {
                val sessions = state.sessions.filter { it.id in state.selectedSessionIds }
                    .map { it.copy(groupName = targetGroup) }
                repository.updateAll(sessions)
            }

            // Move entire group trees
            for (groupPath in state.selectedGroupPaths) {
                val treeSessions: List<Session> = repository.getSessionsInGroupTree(groupPath)
                val updated: List<Session> = treeSessions.map { ses ->
                    val newGroup = if (ses.groupName == groupPath) {
                        targetGroup
                    } else {
                        targetGroup + ses.groupName.removePrefix(groupPath)
                    }
                    ses.copy(groupName = newGroup)
                }
                repository.updateAll(updated)
                preferences.removeKnownGroupTree(groupPath)
            }

            preferences.addKnownGroup(targetGroup)

            _uiState.value = state.copy(
                statusMessage = "Moved to $targetGroup",
                selectionMode = false,
                selectedSessionIds = emptySet(),
                selectedGroupPaths = emptySet(),
            )
        }
    }

    private fun applyCurrentGroup(session: Session): Session {
        val currentGroup = _uiState.value.selectedGroup
        return if (currentGroup.isNotEmpty()) session.copy(groupName = currentGroup) else session
    }

    fun clearStatusMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = null)
    }

    class Factory(
        private val repository: SessionRepository,
        private val connPool: ConnPool,
        private val preferences: AppPreferences,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, connPool, preferences, appContext) as T
        }
    }
}
