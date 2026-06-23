package com.sunbeat.sshclient.domain.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe pool of active [TerminalSession] instances keyed by session ID.
 *
 * Exposes [activeIds] as a [StateFlow] so that UI layers can observe which
 * connections are currently open.
 *
 * All public methods are [suspend] functions so they can safely acquire the
 * internal [Mutex].  Callers must be inside a coroutine scope.
 */
class ConnPool {

    private val mutex = Mutex()
    private val sessions = mutableMapOf<Long, TerminalSession>()
    private val _activeIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeIds: StateFlow<Set<Long>> = _activeIds.asStateFlow()

    // ── Queries ───────────────────────────────────────────────────────────

    /** Look up an active session by its database ID, or `null`. */
    suspend fun get(id: Long): TerminalSession? = mutex.withLock { sessions[id] }

    /** `true` when a session with [id] is currently tracked. */
    suspend fun isConnected(id: Long): Boolean = mutex.withLock { id in sessions }

    /** Number of currently active sessions. */
    suspend fun activeCount(): Int = mutex.withLock { sessions.size }

    // ── Mutations ─────────────────────────────────────────────────────────

    /** Register a new (or replacement) session. */
    suspend fun add(session: TerminalSession) {
        mutex.withLock {
            sessions[session.id] = session
            _activeIds.value = sessions.keys.toSet()
        }
    }

    /**
     * Close and remove an active session.
     * No-op if the session is not tracked.
     */
    suspend fun remove(id: Long) {
        mutex.withLock {
            sessions[id]?.close()
            sessions.remove(id)
            _activeIds.value = sessions.keys.toSet()
        }
    }

    /** Close and remove every tracked session. */
    suspend fun closeAll() {
        mutex.withLock {
            sessions.values.forEach { it.close() }
            sessions.clear()
            _activeIds.value = emptySet()
        }
    }
}
