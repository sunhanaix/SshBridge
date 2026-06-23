package com.sunbeat.sshclient.domain.parser

import com.sunbeat.sshclient.domain.model.AuthType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScrtIniParserTest {

    private val sampleIni = listOf(
        """S:"Username"=zsan""",
        """D:"[SSH2] Port"=000071a7""",
        """S:"Hostname"=www.sunbeatus.com""",
        """S:"Protocol Name"=SSH2""",
        """S:"Password V2"=03:1ba3b3825b360ab57f9ae42a89897416""",
        """S:"Identity Filename V2"=""",
        """S:"Firewall Name"=None""",
        """S:"Cipher List"=aes256-ctr,aes128-ctr""",
        """S:"Key Exchange Algorithms"=ecdh-sha2-nistp521""",
        """S:"Host Key Algorithms"=ssh-ed25519""",
        """S:"SSH2 Authentications V2"=password,publickey""",
        """S:"Emulation"=VT100""",
        """D:"Rows"=00000021""",
        """D:"Cols"=00000084""",
        """D:"Enable Agent Forwarding"=00000002""",
        """D:"Request pty"=00000001""",
    )

    @Test
    fun `parse raw ini entries`() {
        val entries = ScrtIniParser.parse(sampleIni)
        assertEquals(16, entries.size)
        assertEquals('S', entries[0].type)
        assertEquals("Username", entries[0].key)
        assertEquals("zsan", entries[0].value)
    }

    @Test
    fun `parse DWORD hex values`() {
        val entries = ScrtIniParser.parse(sampleIni)
        assertNotNull(entries.find { it.key == "[SSH2] Port" && it.value == "000071a7" })
    }

    @Test
    fun `parse session from entries`() {
        val entries = ScrtIniParser.parse(sampleIni)
        val session = ScrtIniParser.parseSession(entries, "zsan-bandwagon", "mygroup")

        assertEquals("zsan-bandwagon", session.name)
        assertEquals("mygroup", session.groupName)
        assertEquals("www.sunbeatus.com", session.hostname)
        assertEquals(29095, session.port) // 0x71a7
        assertEquals("zsan", session.username)
        assertEquals("SSH2", session.protocol)
        assertEquals(AuthType.BOTH, session.authType)
        assertEquals("03:1ba3b3825b360ab57f9ae42a89897416", session.encryptedPassword)
        assertNull(session.identityFile)
        assertNull(session.jumpHost) // "None" -> null
        assertEquals("aes256-ctr,aes128-ctr", session.ciphers)
        assertEquals("VT100", session.terminalType)
        assertEquals(33, session.rows) // 0x21
        assertEquals(132, session.cols) // 0x84
        assertEquals(true, session.agentForwarding) // 2 = enabled
        assertEquals(true, session.requestPty)
    }

    @Test
    fun `missing keys get defaults`() {
        val session = ScrtIniParser.parseSession(emptyList(), "test", "group")
        assertEquals("", session.hostname)
        assertEquals(22, session.port)
        assertEquals(24, session.rows)
        assertEquals(80, session.cols)
    }

    @Test
    fun `jump host mapping - None becomes null`() {
        val entries = listOf("""S:"Firewall Name"=None""")
        val parsed = ScrtIniParser.parse(entries)
        val session = ScrtIniParser.parseSession(parsed, "test", "")
        assertNull(session.jumpHost)
    }

    @Test
    fun `jump host mapping - actual host`() {
        val entries = listOf("""S:"Firewall Name"=jump.internal.com""")
        val parsed = ScrtIniParser.parse(entries)
        val session = ScrtIniParser.parseSession(parsed, "test", "")
        assertEquals("jump.internal.com", session.jumpHost)
    }

    @Test
    fun `ignore binary Z type entries`() {
        val lines = listOf(
            """S:"Hostname"=example.com""",
            """Z:"Port Forward Table V3"=00000000""",
            """Z:"Description"=00000000""",
        )
        val entries = ScrtIniParser.parse(lines)
        assertEquals(1, entries.size)
        assertEquals("Hostname", entries[0].key)
    }

    @Test
    fun `auth type detection - password only`() {
        val entries = ScrtIniParser.parse(listOf(
            """S:"SSH2 Authentications V2"=password""",
            """S:"Identity Filename V2"=""",
        ))
        assertEquals(AuthType.PASSWORD, ScrtIniParser.parseSession(entries, "t", "").authType)
    }

    @Test
    fun `auth type detection - key only`() {
        val entries = ScrtIniParser.parse(listOf(
            """S:"SSH2 Authentications V2"=publickey""",
            """S:"Identity Filename V2"=C:\keys\id_rsa""",
        ))
        assertEquals(AuthType.KEY, ScrtIniParser.parseSession(entries, "t", "").authType)
    }

    @Test
    fun `auth type detection - both`() {
        val entries = ScrtIniParser.parse(listOf(
            """S:"SSH2 Authentications V2"=password,publickey""",
            """S:"Identity Filename V2"=C:\keys\id_rsa""",
        ))
        assertEquals(AuthType.BOTH, ScrtIniParser.parseSession(entries, "t", "").authType)
    }
}
