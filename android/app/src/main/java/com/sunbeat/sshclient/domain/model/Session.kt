package com.sunbeat.sshclient.domain.model

data class Session(
    val id: Long = 0,
    val name: String,
    val groupName: String = "",
    val hostname: String,
    val port: Int = 22,
    val username: String = "",
    val protocol: String = "SSH2",
    val authType: AuthType = AuthType.PASSWORD,
    val encryptedPassword: String? = null,
    val plainPassword: String? = null,
    val identityFile: String? = null,
    val keyContent: String? = null,
    val jumpHost: String? = null,
    val ciphers: String = "",
    val kexAlgorithms: String = "",
    val hostKeyAlgorithms: String = "",
    val authMethods: String = "",
    val terminalType: String = "xterm-256color",
    val rows: Int = 24,
    val cols: Int = 80,
    val colorScheme: String = "",
    val agentForwarding: Boolean = false,
    val requestPty: Boolean = true,
    val defaultRemoteDir: String? = null,
    val sortOrder: Int = 0,
    val isFavorite: Boolean = false,
    val sourceFile: String = ""
)

enum class AuthType { PASSWORD, KEY, BOTH }
