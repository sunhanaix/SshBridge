package com.sunbeat.sshclient.data.import

import android.util.Base64
import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Import session configuration from a base64-encoded JSON payload or a
 * WLAN sync server URL.
 *
 * ## Wire format (shared with desktop tooling)
 *
 * JSON keys are deliberately short to keep QR codes compact:
 *
 * ```json
 * {
 *   "v": 1,
 *   "s": [
 *     {
 *       "n": "Session Name",
 *       "h": "hostname",
 *       "p": 22,
 *       "u": "username",
 *       "a": "PASSWORD",
 *       "pw": "password",
 *       "g": "Group/Work",
 *       "k": "/path/to/key",
 *       "j": "jumphost",
 *       "sh": true
 *     }
 *   ]
 * }
 * ```
 *
 * ## Two QR code modes
 *
 * - Direct config:  `SSHCONF:base64data...` — base64-decoded and parsed directly.
 * - Sync server:    `SSHCONFSYNC:http://ip:port` — the app fetches the
 *                    config JSON from the URL (served by the desktop tool).
 *
 * On the wire the JSON is base64-encoded (no padding, URL-safe preferred).
 * Both the desktop sync server response and direct QR codes use `SSHCONF:`
 * wrapping.
 */
object ConfigImporter {

    private const val PREFIX = "SSHCONF:"
    const val SYNC_PREFIX = "SSHCONFSYNC:"
    private const val FORMAT_VERSION = 1

    /** Result of parsing a base64 config string. */
    data class ImportResult(
        val sessions: List<Session> = emptyList(),
        val error: String? = null,
    )

    /**
     * Parse a base64 string (optionally prefixed with `SSHCONF:`) into
     * a list of [Session] objects ready for insertion.
     */
    fun parse(input: String): ImportResult {
        val cleaned = input.trim().removePrefix(PREFIX).trim()
        if (cleaned.isEmpty()) {
            return ImportResult(error = "Empty input")
        }

        val json: String
        try {
            val decoded = Base64.decode(cleaned, Base64.DEFAULT)
            json = String(decoded, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return ImportResult(error = "Invalid base64 encoding")
        }

        return parseJson(json)
    }

    /**
     * Encode a list of sessions into a base64 string suitable for QR code
     * generation or clipboard sharing (WITHOUT the prefix — add if desired).
     */
    fun encode(sessions: List<Session>): String {
        val json = sessionsToJson(sessions)
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** Encode with the SSHCONF: prefix for clipboard sharing. */
    fun encodeWithPrefix(sessions: List<Session>): String = "$PREFIX${encode(sessions)}"

    // ── Sync server URL support ──────────────────────────────────

    /** Returns true if [input] is a sync server URL (starts with `SSHCONFSYNC:`). */
    fun isSyncUrl(input: String): Boolean =
        input.trimStart().startsWith(SYNC_PREFIX)

    /**
     * Fetch config from a WLAN sync server URL.
     *
     * [input] must start with `SSHCONFSYNC:` followed by an HTTP URL.
     * The server is expected to respond with an `SSHCONF:base64...` body.
     */
    suspend fun fetchFromSyncUrl(input: String): ImportResult {
        val rawUrl = input.trimStart().removePrefix(SYNC_PREFIX).trim()
        if (rawUrl.isEmpty()) {
            return ImportResult(error = "Empty sync URL")
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(rawUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext ImportResult(error = "Server returned $responseCode")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                if (body.isBlank()) {
                    return@withContext ImportResult(error = "Empty response from server")
                }

                // The server sends SSHCONF:base64... — parse normally
                parse(body)
            } catch (e: java.net.SocketTimeoutException) {
                ImportResult(error = "Connection timed out")
            } catch (e: java.net.ConnectException) {
                ImportResult(error = "Cannot reach sync server — same WiFi?")
            } catch (e: Exception) {
                ImportResult(error = "Sync error: ${e.message}")
            }
        }
    }

    // ── internals ──────────────────────────────────────────────

    private fun parseJson(json: String): ImportResult {
        val root: JSONObject
        try {
            root = JSONObject(json)
        } catch (_: Exception) {
            return ImportResult(error = "Invalid JSON")
        }

        val version = root.optInt("v", 0)
        if (version != FORMAT_VERSION) {
            return ImportResult(error = "Unsupported format version $version (expected $FORMAT_VERSION)")
        }

        val arr: JSONArray = root.optJSONArray("s")
            ?: return ImportResult(error = "Missing 'sessions' array")

        val sessions = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            sessions.add(
                Session(
                    name = obj.optString("n", obj.optString("name", "")),
                    hostname = obj.optString("h", obj.optString("hostname", "")),
                    port = obj.optInt("p", obj.optInt("port", 22)),
                    username = obj.optString("u", obj.optString("username", "")),
                    authType = parseAuthType(obj.optString("a", obj.optString("authType", "PASSWORD"))),
                    plainPassword = obj.optString("pw", obj.optString("password", "")).ifEmpty { null },
                    groupName = obj.optString("g", obj.optString("groupName", "")),
                    identityFile = obj.optString("k", obj.optString("identityFile", "")).ifEmpty { null },
                    keyContent = obj.optString("kc", "").ifEmpty { null },
                    jumpHost = obj.optString("j", obj.optString("jumpHost", "")).ifEmpty { null },
                    sourceFile = "import",
                ),
            )
        }

        if (sessions.isEmpty()) {
            return ImportResult(error = "No sessions found in payload")
        }

        return ImportResult(sessions = sessions)
    }

    private fun parseAuthType(s: String): AuthType = when (s.uppercase()) {
        "KEY" -> AuthType.KEY
        "BOTH" -> AuthType.BOTH
        else -> AuthType.PASSWORD
    }

    private fun sessionsToJson(sessions: List<Session>): String {
        val arr = JSONArray()
        for (s in sessions) {
            val obj = JSONObject().apply {
                put("n", s.name)
                put("h", s.hostname)
                put("p", s.port)
                put("u", s.username)
                put("a", s.authType.name)
                s.plainPassword?.let { put("pw", it) }
                if (s.groupName.isNotEmpty()) put("g", s.groupName)
                s.identityFile?.let { put("k", it) }
                s.keyContent?.let { put("kc", it) }
                s.jumpHost?.let { put("j", it) }
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("v", FORMAT_VERSION)
            put("s", arr)
        }.toString()
    }
}
