package com.sunbeat.sshclient.ui.terminal

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sunbeat.sshclient.data.local.toEntity
import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.domain.repository.SessionRepository
import com.sunbeat.sshclient.domain.ssh.ConnPool
import com.sunbeat.sshclient.domain.ssh.TerminalSession
import com.trilead.ssh2.Connection
import com.trilead.ssh2.InteractiveCallback
import com.trilead.ssh2.ServerHostKeyVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "SSHTerm"

/** Trust-on-first-use: always accept.  P2 will add known_hosts persistence. */
private val AcceptAllHostKeys = ServerHostKeyVerifier { _, _, _, _ -> true }

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val step: String = "Connecting…") : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class TerminalUiState(
    val sessionName: String = "",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
)

class TerminalViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val connPool: ConnPool,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    var terminalSession: TerminalSession? = null
        private set

    // Held for cleanup — closed in cleanup() / onCleared()
    private var sshConnection: Connection? = null
    private var sshSession: com.trilead.ssh2.Session? = null

    init {
        Log.d(TAG, "TerminalViewModel init for sessionId=$sessionId")
        viewModelScope.launch {
            val session = repository.getById(sessionId)
            if (session != null) {
                _uiState.value = _uiState.value.copy(sessionName = session.name)
                Log.d(TAG, "Starting connect to ${session.hostname}:${session.port}")
                connect(session)
            } else {
                Log.e(TAG, "Session not found: $sessionId")
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error("Session not found"),
                )
            }
        }
    }

    fun connect(session: Session) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Connecting("Connecting to ${session.hostname}:${session.port}…")
            )
            try {
                withContext(Dispatchers.IO) {
                    // 1. TCP + SSH handshake
                    val conn = Connection(session.hostname, session.port)
                    conn.connect(AcceptAllHostKeys)
                    sshConnection = conn
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connecting("SSH handshake OK, authenticating…")
                    )
                    Log.d(TAG, "TCP+SSH handshake OK")

                    // 2. Authenticate
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connecting("Authenticating as ${session.username} (${session.authType.name.lowercase()})…")
                    )
                    authenticate(conn, session)
                    Log.d(TAG, "Authentication OK")

                    // 3. Open session + PTY
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connecting("Opening session, allocating PTY…")
                    )
                    val s = conn.openSession()
                    sshSession = s
                    s.requestPTY(
                        session.terminalType,
                        session.cols,
                        session.rows,
                        0, 0, null
                    )
                    Log.d(TAG, "PTY allocated (${session.cols}x${session.rows}, ${session.terminalType})")

                    // 4. Start shell
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connecting("Starting shell…")
                    )
                    s.startShell()
                    Log.d(TAG, "Shell started")

                    // 5. Build TerminalSession
                    val entity = session.toEntity()
                    val ts = TerminalSession(
                        dbSession = entity,
                        sshClient = conn,
                        ptyInputStream = s.stdout,
                        ptyOutputStream = s.stdin,
                    )
                    Log.d(TAG, "TerminalSession created (cols=${ts.emulator.mColumns}, rows=${ts.emulator.mRows})")

                    terminalSession = ts
                    connPool.add(ts)
                    _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connected)
                    Log.d(TAG, "State → Connected")
                }

                // 5. Start background reader
                terminalSession?.let { ts ->
                    Log.d(TAG, "10. Launching readRemoteOutput…")
                    launch(Dispatchers.IO) {
                        readRemoteOutput(ts)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                cleanup()
                terminalSession = null
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error(e.message ?: "Connection failed"),
                )
            }
        }
    }

    /**
     * Try all applicable auth methods based on [session.authType].
     * Falls back to keyboard-interactive when password auth is unavailable.
     */
    private fun authenticate(conn: Connection, session: Session) {
        val password = session.plainPassword ?: ""

        val authed = when (session.authType) {
            AuthType.PASSWORD -> tryPassword(conn, session.username, password)
            AuthType.KEY -> {
                val keyOk = authenticateWithKey(conn, session)
                if (!keyOk && hasKbInteractive(conn, session.username)) {
                    authenticateWithKeyboardInteractive(conn, session.username, password)
                } else keyOk
            }
            AuthType.BOTH -> {
                val kc = session.keyContent
                if (!kc.isNullOrBlank()) {
                    val keyOk = try {
                        authenticateWithKey(conn, session)
                    } catch (_: Exception) {
                        false
                    }
                    if (!keyOk) tryPassword(conn, session.username, password) else true
                } else {
                    tryPassword(conn, session.username, password)
                }
            }
        }

        if (!authed) {
            val remaining = conn.getRemainingAuthMethods(session.username)
            throw Exception("Authentication failed. Remaining methods: ${remaining?.joinToString() ?: "none"}")
        }
    }

    /** Try password auth, then keyboard-interactive as fallback. */
    private fun tryPassword(conn: Connection, username: String, password: String): Boolean {
        if (conn.authenticateWithPassword(username, password)) return true
        if (hasKbInteractive(conn, username)) {
            return authenticateWithKeyboardInteractive(conn, username, password)
        }
        return false
    }

    private fun hasKbInteractive(conn: Connection, username: String): Boolean {
        val remaining = conn.getRemainingAuthMethods(username) ?: return false
        return remaining.any { it.equals("keyboard-interactive", ignoreCase = true) }
    }

    private fun authenticateWithKeyboardInteractive(
        conn: Connection, username: String, password: String,
    ): Boolean {
        return conn.authenticateWithKeyboardInteractive(username, PasswordKbCallback(password))
    }

    /** Simple keyboard-interactive callback that answers every prompt with the password. */
    private class PasswordKbCallback(private val password: String) : InteractiveCallback {
        override fun replyToChallenge(
            name: String,
            instruction: String,
            numPrompts: Int,
            prompt: Array<String>,
            echo: BooleanArray,
        ): Array<String> = Array(numPrompts) { password }
    }

    private fun authenticateWithKey(conn: Connection, session: Session): Boolean {
        val kc = session.keyContent
            ?: throw Exception("No key content available")
        val keyFile = File.createTempFile("sshkey_", ".pem", appContext.cacheDir)
        try {
            keyFile.writeText(kc)
            return conn.authenticateWithPublicKey(
                session.username, keyFile,
                session.plainPassword ?: ""
            )
        } finally {
            keyFile.delete()
        }
    }

    private suspend fun readRemoteOutput(ts: TerminalSession) {
        Log.d(TAG, "readRemoteOutput: started")
        val buf = ByteArray(4096)
        try {
            while (currentCoroutineContext().isActive) {
                Log.d(TAG, "readRemoteOutput: blocking read…")
                val n = ts.ptyInputStream.read(buf)
                Log.d(TAG, "readRemoteOutput: read returned n=$n")
                if (n < 0) {
                    Log.d(TAG, "readRemoteOutput: EOF — server closed connection")
                    break
                }
                if (n > 0) {
                    val data = buf.copyOf(n)
                    Log.d(TAG, "readRemoteOutput: feeding ${n} bytes to emulator: '${String(data).take(100)}'")
                    ts.feedRemoteData(data)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "readRemoteOutput: IOException: ${e.message}")
        } finally {
            Log.d(TAG, "readRemoteOutput: finished")
            terminalSession = null
            connPool.remove(ts.id)
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Disconnected)
            cleanup()
        }
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup: closing SSH session and connection")
        runCatching { sshSession?.close() }
        runCatching { sshConnection?.close() }
        sshSession = null
        sshConnection = null
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        terminalSession?.let { ts ->
            viewModelScope.launch { connPool.remove(ts.id) }
        }
        terminalSession = null
        _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Disconnected)
        cleanup()
    }

    fun reconnect() {
        Log.d(TAG, "reconnect")
        viewModelScope.launch {
            val session = repository.getById(sessionId)
            if (session != null) connect(session)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared")
        terminalSession?.let { ts ->
            viewModelScope.launch { connPool.remove(ts.id) }
        }
        cleanup()
    }

    class Factory(
        private val sessionId: Long,
        private val repository: SessionRepository,
        private val connPool: ConnPool,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(sessionId, repository, connPool, appContext) as T
        }
    }
}
