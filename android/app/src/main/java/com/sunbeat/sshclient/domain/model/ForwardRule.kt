package com.sunbeat.sshclient.domain.model

data class ForwardRule(
    val id: Long = 0,
    val type: ForwardType,
    val name: String,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val remoteHost: String = "",
    val remotePort: Int = 0,
    val enabled: Boolean = true
)

enum class ForwardType { LOCAL, REMOTE, DYNAMIC }
