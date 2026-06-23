package com.sunbeat.sshclient.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "group_name") val groupName: String = "",
    val hostname: String,
    val port: Int = 22,
    val username: String = "",
    val protocol: String = "SSH2",
    @ColumnInfo(name = "auth_type") val authType: String = "PASSWORD",
    @ColumnInfo(name = "encrypted_password") val encryptedPassword: String? = null,
    @ColumnInfo(name = "plain_password") val plainPassword: String? = null,
    @ColumnInfo(name = "identity_file") val identityFile: String? = null,
    @ColumnInfo(name = "key_content") val keyContent: String? = null,
    @ColumnInfo(name = "jump_host") val jumpHost: String? = null,
    val ciphers: String = "",
    @ColumnInfo(name = "kex_algorithms") val kexAlgorithms: String = "",
    @ColumnInfo(name = "host_key_algorithms") val hostKeyAlgorithms: String = "",
    @ColumnInfo(name = "auth_methods") val authMethods: String = "",
    @ColumnInfo(name = "terminal_type") val terminalType: String = "xterm-256color",
    val rows: Int = 24,
    val cols: Int = 80,
    @ColumnInfo(name = "color_scheme") val colorScheme: String = "",
    @ColumnInfo(name = "agent_forwarding") val agentForwarding: Boolean = false,
    @ColumnInfo(name = "request_pty") val requestPty: Boolean = true,
    @ColumnInfo(name = "default_remote_dir") val defaultRemoteDir: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "source_file") val sourceFile: String = ""
)

fun SessionEntity.toSession(): Session = Session(
    id = id, name = name, groupName = groupName, hostname = hostname,
    port = port, username = username, protocol = protocol,
    authType = AuthType.valueOf(authType),
    encryptedPassword = encryptedPassword, plainPassword = plainPassword,
    identityFile = identityFile, keyContent = keyContent, jumpHost = jumpHost,
    ciphers = ciphers, kexAlgorithms = kexAlgorithms,
    hostKeyAlgorithms = hostKeyAlgorithms, authMethods = authMethods,
    terminalType = terminalType, rows = rows, cols = cols,
    colorScheme = colorScheme, agentForwarding = agentForwarding,
    requestPty = requestPty, defaultRemoteDir = defaultRemoteDir,
    sortOrder = sortOrder, isFavorite = isFavorite, sourceFile = sourceFile
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id, name = name, groupName = groupName, hostname = hostname,
    port = port, username = username, protocol = protocol,
    authType = authType.name,
    encryptedPassword = encryptedPassword, plainPassword = plainPassword,
    identityFile = identityFile, keyContent = keyContent, jumpHost = jumpHost,
    ciphers = ciphers, kexAlgorithms = kexAlgorithms,
    hostKeyAlgorithms = hostKeyAlgorithms, authMethods = authMethods,
    terminalType = terminalType, rows = rows, cols = cols,
    colorScheme = colorScheme, agentForwarding = agentForwarding,
    requestPty = requestPty, defaultRemoteDir = defaultRemoteDir,
    sortOrder = sortOrder, isFavorite = isFavorite, sourceFile = sourceFile
)
