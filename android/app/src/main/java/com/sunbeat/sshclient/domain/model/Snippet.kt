package com.sunbeat.sshclient.domain.model

data class Snippet(
    val id: Long = 0,
    val label: String,
    val command: String,
    val group: String? = null,
    val isMacro: Boolean = false,
    val sortOrder: Int = 0
)
