package com.sunbeat.sshclient.domain.model

data class SshKey(
    val id: Long = 0,
    val name: String,
    val type: KeyType,
    val bits: Int,
    val publicKey: String = "",
    val privateKeyPath: String,
    val hasPassphrase: Boolean = false,
    val fingerprint: String = ""
)

enum class KeyType { RSA, ED25519, ECDSA }
