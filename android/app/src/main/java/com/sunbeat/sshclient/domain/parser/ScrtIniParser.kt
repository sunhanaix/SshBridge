package com.sunbeat.sshclient.domain.parser

import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session

data class IniEntry(val type: Char, val key: String, val value: String)

object ScrtIniParser {

    private val entryRegex = Regex("""^([SDB]):"([^"]+)"=(.*)""")

    fun parse(lines: List<String>): List<IniEntry> {
        return lines.mapNotNull { line ->
            entryRegex.matchEntire(line.trim())?.let { match ->
                val type = match.groupValues[1][0]
                val key = match.groupValues[2]
                val value = match.groupValues[3]
                IniEntry(type, key, value)
            }
        }
    }

    fun parseSession(entries: List<IniEntry>, fileName: String, groupName: String): Session {
        val map = entries.associateBy { it.key }

        fun stringValue(key: String, default: String = ""): String =
            map[key]?.value?.ifEmpty { default } ?: default

        fun hexInt(key: String, default: Int): Int =
            map[key]?.value?.toIntOrNull(16) ?: default

        fun boolFlag(key: String, default: Boolean = false): Boolean {
            val v = map[key]?.value ?: return default
            // 00000001 or 00000002 = enabled, 00000000 = disabled
            return when (v) {
                "00000001", "00000002" -> true
                else -> false
            }
        }

        val hostname = stringValue("Hostname")
        val port = hexInt("[SSH2] Port", 22)
        val username = stringValue("Username")
        val protocol = stringValue("Protocol Name", "SSH2")
        val encryptedPassword = stringValue("Password V2").ifEmpty { null }
        val identityFile = stringValue("Identity Filename V2").ifEmpty { null }
        val jumpHost = stringValue("Firewall Name").let { if (it == "None" || it.isEmpty()) null else it }
        val ciphers = stringValue("Cipher List")
        val kexAlgorithms = stringValue("Key Exchange Algorithms")
        val hostKeyAlgorithms = stringValue("Host Key Algorithms")
        val authMethods = stringValue("SSH2 Authentications V2")
        val terminalType = stringValue("Emulation", "xterm-256color")
        val rows = hexInt("Rows", 24)
        val cols = hexInt("Cols", 80)
        val colorScheme = stringValue("Color Scheme")
        val agentForwarding = boolFlag("Enable Agent Forwarding")
        val requestPty = boolFlag("Request pty", true)
        val defaultRemoteDir = stringValue("Sftp Tab Remote Directory").ifEmpty { null }

        val authType = when {
            authMethods.contains("publickey") && authMethods.contains("password") -> AuthType.BOTH
            authMethods.contains("publickey") && identityFile != null -> AuthType.KEY
            authMethods.contains("publickey") -> AuthType.KEY
            else -> AuthType.PASSWORD
        }

        return Session(
            name = fileName,
            groupName = groupName,
            hostname = hostname,
            port = port,
            username = username,
            protocol = protocol,
            authType = authType,
            encryptedPassword = encryptedPassword,
            identityFile = identityFile,
            jumpHost = jumpHost,
            ciphers = ciphers,
            kexAlgorithms = kexAlgorithms,
            hostKeyAlgorithms = hostKeyAlgorithms,
            authMethods = authMethods,
            terminalType = terminalType,
            rows = rows,
            cols = cols,
            colorScheme = colorScheme,
            agentForwarding = agentForwarding,
            requestPty = requestPty,
            defaultRemoteDir = defaultRemoteDir,
            sourceFile = "$groupName/$fileName.ini"
        )
    }
}
