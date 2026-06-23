# SSH Android Client — P1 Core Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the core pipeline from "tap a connection in the list" through "see a working SSH terminal" — INI parsing, Room DB, connection list UI, terminal view with sshlib + Termux TerminalView wired together.

**Architecture:** Single-Activity Compose app with manual DI via AppContainer. Domain layer is pure Kotlin. sshlib handles SSH protocol, Termux TerminalView renders the VT100 emulator. SCRT .ini files parsed into Room entities; ConnPool manages active TerminalSessions.

**Tech Stack:** Kotlin 2.1.x, AGP 8.7.x, Compose BOM 2025.06, Room 2.7.1, DataStore 1.1.3, Coroutines 1.9.0, ConnectBot sshlib, Termux terminal-view

## Global Constraints

- Package: `com.sunbeat.sshclient`
- Min SDK 26, Target SDK 35
- All passwords/keys held in memory only, never logged
- SCRT password decryption is out of scope (encryptedPassword stored as-is, plainPassword as user-entered fallback)
- INI files: type-prefixed format `S:"Key"=value` / `D:"Key"=hex` / `B:"Key"=hexbytes` / `Z:"Key"=empty`
- Terminal type default: `xterm-256color`, scrollback default: 32000 lines

---

### Task 1: Project Scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/proguard-rules.pro`

**Produces:** Buildable empty Android project with all dependencies wired.

- [ ] **Step 1: Create settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "sshclient"
include(":app")
```

- [ ] **Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.31" apply false
}
```

- [ ] **Step 3: Create app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sunbeat.sshclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sunbeat.sshclient"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("androidx.datastore:datastore-preferences:1.1.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.github.connectbot:sshlib:2.2.1")
    implementation("com.termux:terminal-view:0.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

- [ ] **Step 4: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".SshApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.SshClient"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Create res/values/strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SSH Client</string>
    <string name="no_connections">No connections yet</string>
    <string name="import_hint">Tap + to import SCRT sessions</string>
    <string name="connecting">Connecting…</string>
    <string name="connected">Connected</string>
    <string name="disconnected">Disconnected</string>
    <string name="error_connect">Connection failed</string>
    <string name="group_all">All Sessions</string>
</resources>
```

- [ ] **Step 7: Create res/values/themes.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.SshClient" parent="android:Theme.Material.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

- [ ] **Step 8: Create app/proguard-rules.pro (empty for now)**

```
# sshlib
-keep class org.connectbot.** { *; }
# Termux terminal
-keep class com.termux.** { *; }
```

- [ ] **Step 9: Verify project syncs**

Run: `./gradlew assembleDebug` from project root
Expected: BUILD SUCCESSFUL (with placeholder source files — add a minimal MainActivity.kt and SshApp.kt stubs to get a clean build)

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app/build.gradle.kts gradle.properties
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/res/values/themes.xml app/proguard-rules.pro
git commit -m "feat: scaffold Android project with Gradle, Compose, Room, sshlib, terminal-view"
```

---

### Task 2: Domain Models

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/model/Session.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/model/SshKey.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/model/ForwardRule.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/model/Snippet.kt`

**Produces:** Pure Kotlin data classes used across all layers.

- [ ] **Step 1: Create Session.kt**

```kotlin
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
```

- [ ] **Step 2: Create SshKey.kt**

```kotlin
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
```

- [ ] **Step 3: Create ForwardRule.kt**

```kotlin
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
```

- [ ] **Step 4: Create Snippet.kt**

```kotlin
package com.sunbeat.sshclient.domain.model

data class Snippet(
    val id: Long = 0,
    val label: String,
    val command: String,
    val group: String? = null,
    val isMacro: Boolean = false,
    val sortOrder: Int = 0
)
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/domain/model/
git commit -m "feat: add domain models — Session, SshKey, ForwardRule, Snippet"
```

---

### Task 3: SCRT INI Parser

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/parser/ScrtIniParser.kt`
- Create: `app/src/test/java/com/sunbeat/sshclient/domain/parser/ScrtIniParserTest.kt`

**Interfaces:**
- Produces: `ScrtIniParser.parse(lines: List<String>): List<IniEntry>`
- Produces: `ScrtIniParser.parseSession(entries: List<IniEntry>, fileName: String, groupName: String): Session`

**Consumes:** Session from Task 2

- [ ] **Step 1: Write test data**

Create a test resource representing a minimal SCRT .ini file. We'll inline strings in the test.

```kotlin
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
        assertEquals(15, entries.size)
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
        assertEquals(AuthType.PASSWORD, session.authType)
        assertEquals("03:1ba3b3825b360ab57f9ae42a89897416", session.encryptedPassword)
        assertNull(session.identityFile)
        assertNull(session.jumpHost) // "None" → null
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.sunbeat.sshclient.domain.parser.ScrtIniParserTest"`
Expected: compilation failure — `ScrtIniParser` not defined

- [ ] **Step 3: Implement ScrtIniParser**

```kotlin
package com.sunbeat.sshclient.domain.parser

import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session

data class IniEntry(val type: Char, val key: String, val value: String)

object ScrtIniParser {

    private val entryRegex = Regex("""^([SDBZ]):"([^"]+)"=(.*)""")

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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.sunbeat.sshclient.domain.parser.ScrtIniParserTest"`
Expected: all 10 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/domain/parser/
git add app/src/test/java/com/sunbeat/sshclient/domain/parser/
git commit -m "feat: add SCRT INI parser with field mapping to Session model"
```

---

### Task 4: Room Database

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/data/local/SessionEntity.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/data/local/SessionDao.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/data/local/AppDatabase.kt`
- Create: `app/src/androidTest/java/com/sunbeat/sshclient/data/local/SessionDaoTest.kt`

**Consumes:** Session domain model from Task 2

- [ ] **Step 1: Create SessionEntity.kt**

```kotlin
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
    identityFile = identityFile, jumpHost = jumpHost,
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
    identityFile = identityFile, jumpHost = jumpHost,
    ciphers = ciphers, kexAlgorithms = kexAlgorithms,
    hostKeyAlgorithms = hostKeyAlgorithms, authMethods = authMethods,
    terminalType = terminalType, rows = rows, cols = cols,
    colorScheme = colorScheme, agentForwarding = agentForwarding,
    requestPty = requestPty, defaultRemoteDir = defaultRemoteDir,
    sortOrder = sortOrder, isFavorite = isFavorite, sourceFile = sourceFile
)
```

- [ ] **Step 2: Create SessionDao.kt**

```kotlin
package com.sunbeat.sshclient.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY sort_order ASC, name ASC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT DISTINCT group_name FROM sessions WHERE group_name != '' ORDER BY group_name ASC")
    fun getAllGroups(): Flow<List<String>>

    @Query("SELECT * FROM sessions WHERE group_name = :group ORDER BY sort_order ASC, name ASC")
    fun getSessionsByGroup(group: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SessionEntity>)

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}
```

- [ ] **Step 3: Create AppDatabase.kt**

```kotlin
package com.sunbeat.sshclient.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sshclient.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 4: Create SessionDaoTest.kt**

```kotlin
package com.sunbeat.sshclient.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.sessionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insert_and_query() = runTest {
        val entity = SessionEntity(
            name = "Test Server",
            hostname = "192.168.1.1",
            port = 22,
            username = "root",
            groupName = "Production"
        )
        val id = dao.insert(entity)
        val retrieved = dao.getById(id)
        assertEquals("Test Server", retrieved?.name)
        assertEquals("192.168.1.1", retrieved?.hostname)
        assertEquals("Production", retrieved?.groupName)
    }

    @Test
    fun insertAll_and_getAll() = runTest {
        dao.insertAll(listOf(
            SessionEntity(name = "A", hostname = "a.com", groupName = "G1", sortOrder = 0),
            SessionEntity(name = "B", hostname = "b.com", groupName = "G1", sortOrder = 1),
            SessionEntity(name = "C", hostname = "c.com", groupName = "G2", sortOrder = 0),
        ))
        val all = dao.getAllSessions().first()
        assertEquals(3, all.size)
    }

    @Test
    fun groups_distinct() = runTest {
        dao.insertAll(listOf(
            SessionEntity(name = "A", hostname = "a.com", groupName = "Production"),
            SessionEntity(name = "B", hostname = "b.com", groupName = "Production"),
            SessionEntity(name = "C", hostname = "c.com", groupName = "Staging"),
        ))
        val groups = dao.getAllGroups().first()
        assertEquals(2, groups.size)
        assertEquals("Production", groups[0])
    }

    @Test
    fun getByGroup_filters_correctly() = runTest {
        dao.insertAll(listOf(
            SessionEntity(name = "A", hostname = "a.com", groupName = "G1"),
            SessionEntity(name = "B", hostname = "b.com", groupName = "G2"),
        ))
        val g1Sessions = dao.getSessionsByGroup("G1").first()
        assertEquals(1, g1Sessions.size)
        assertEquals("A", g1Sessions[0].name)
    }

    @Test
    fun delete_removes_record() = runTest {
        val id = dao.insert(SessionEntity(name = "ToDelete", hostname = "x.com"))
        assertEquals(1, dao.count())
        dao.deleteById(id)
        assertEquals(0, dao.count())
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew connectedAndroidTest --tests "com.sunbeat.sshclient.data.local.SessionDaoTest"`
Expected: 5 tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/data/local/
git add app/src/androidTest/java/com/sunbeat/sshclient/data/local/
git commit -m "feat: add Room DB — SessionEntity, SessionDao, AppDatabase"
```

---

### Task 5: SessionRepository

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/repository/SessionRepository.kt`
- Create: `app/src/test/java/com/sunbeat/sshclient/domain/repository/SessionRepositoryTest.kt`

**Consumes:** SessionDao (Task 4), ScrtIniParser (Task 3)
**Produces:** `SessionRepository` — single source of truth for sessions, Flow-based reactive queries

- [ ] **Step 1: Create SessionRepository.kt**

```kotlin
package com.sunbeat.sshclient.domain.repository

import com.sunbeat.sshclient.data.local.SessionDao
import com.sunbeat.sshclient.data.local.toEntity
import com.sunbeat.sshclient.data.local.toSession
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.domain.parser.ScrtIniParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class SessionRepository(private val dao: SessionDao) {

    fun getAllSessions(): Flow<List<Session>> =
        dao.getAllSessions().map { entities -> entities.map { it.toSession() } }

    fun getSessionsByGroup(group: String): Flow<List<Session>> =
        dao.getSessionsByGroup(group).map { entities -> entities.map { it.toSession() } }

    fun getAllGroups(): Flow<List<String>> = dao.getAllGroups()

    suspend fun getById(id: Long): Session? = dao.getById(id)?.toSession()

    suspend fun insert(session: Session): Long = dao.insert(session.toEntity())

    suspend fun update(session: Session) = dao.update(session.toEntity())

    suspend fun delete(session: Session) = dao.delete(session.toEntity())

    suspend fun count(): Int = dao.count()

    /**
     * Scan a directory recursively for .ini files, parse them,
     * and insert into the database. Returns count of imported sessions.
     */
    suspend fun importFromDirectory(dirPath: String): Int {
        val dir = File(dirPath)
        if (!dir.isDirectory) return 0

        val imported = mutableListOf<Session>()
        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.equals("ini", ignoreCase = true)) {
                val lines = file.readLines()
                val entries = ScrtIniParser.parse(lines)
                if (entries.any { it.key == "Hostname" }) {
                    val groupName = file.parentFile?.relativeTo(dir)?.path
                        ?.replace(File.separatorChar, '/') ?: ""
                    val session = ScrtIniParser.parseSession(
                        entries,
                        file.nameWithoutExtension,
                        groupName
                    )
                    imported.add(session)
                }
            }
        }

        if (imported.isNotEmpty()) {
            dao.insertAll(imported.map { it.toEntity() })
        }
        return imported.size
    }
}
```

- [ ] **Step 2: Create SessionRepositoryTest.kt**

```kotlin
package com.sunbeat.sshclient.domain.repository

import com.sunbeat.sshclient.data.local.SessionDao
import com.sunbeat.sshclient.data.local.SessionEntity
import com.sunbeat.sshclient.data.local.toSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class SessionRepositoryTest {

    private val dao: SessionDao = mock()
    private val repo = SessionRepository(dao)

    @Test
    fun `getAllSessions maps entities to domain model`() = runTest {
        val entity = SessionEntity(
            id = 1, name = "Test", hostname = "example.com",
            port = 2222, username = "root", groupName = "Prod"
        )
        whenever(dao.getAllSessions()).thenReturn(flowOf(listOf(entity)))

        val sessions = repo.getAllSessions().first()
        assertEquals(1, sessions.size)
        assertEquals("Test", sessions[0].name)
        assertEquals("example.com", sessions[0].hostname)
        assertEquals(2222, sessions[0].port)
        assertEquals("Prod", sessions[0].groupName)
    }

    @Test
    fun `getAllGroups delegates to dao`() = runTest {
        whenever(dao.getAllGroups()).thenReturn(flowOf(listOf("Prod", "Staging")))
        val groups = repo.getAllGroups().first()
        assertEquals(listOf("Prod", "Staging"), groups)
    }

    @Test
    fun `insert delegates to dao insert`() = runTest {
        val session = com.sunbeat.sshclient.domain.model.Session(name = "A", hostname = "a.com")
        whenever(dao.insert(any())).thenReturn(42L)
        val id = repo.insert(session)
        assertEquals(42L, id)
    }

    @Test
    fun `count delegates to dao count`() = runTest {
        whenever(dao.count()).thenReturn(5)
        assertEquals(5, repo.count())
    }
}
```

- [ ] **Step 3: Add mockito-kotlin dependency for tests** — append to `app/build.gradle.kts` dependencies block:

```kotlin
testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.sunbeat.sshclient.domain.repository.SessionRepositoryTest"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/domain/repository/
git add app/src/test/java/com/sunbeat/sshclient/domain/repository/
git add app/build.gradle.kts
git commit -m "feat: add SessionRepository — Flow-based queries + INI directory import"
```

---

### Task 6: AppPreferences + DI Container + Application

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/data/preferences/AppPreferences.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/di/AppContainer.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/SshApp.kt`

**Consumes:** SessionRepository (Task 5), AppDatabase (Task 4)

- [ ] **Step 1: Create AppPreferences.kt**

```kotlin
package com.sunbeat.sshclient.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = intPreferencesKey("font_size")
        val SCROLLBACK_LINES = intPreferencesKey("scrollback_lines")
        val TERMINAL_TYPE = stringPreferencesKey("terminal_type")
        val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val BELL_TYPE = stringPreferencesKey("bell_type")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val IDLE_TIMEOUT = intPreferencesKey("idle_timeout")
    }

    val theme: Flow<String> = context.dataStore.data.map { it[THEME] ?: "dark" }
    val fontSize: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE] ?: 14 }
    val scrollbackLines: Flow<Int> = context.dataStore.data.map { it[SCROLLBACK_LINES] ?: 32000 }
    val terminalType: Flow<String> = context.dataStore.data.map { it[TERMINAL_TYPE] ?: "xterm-256color" }
    val cursorStyle: Flow<String> = context.dataStore.data.map { it[CURSOR_STYLE] ?: "block" }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[AUTO_RECONNECT] ?: false }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[THEME] = value }
    }

    suspend fun setFontSize(value: Int) {
        context.dataStore.edit { it[FONT_SIZE] = value }
    }

    suspend fun setScrollbackLines(value: Int) {
        context.dataStore.edit { it[SCROLLBACK_LINES] = value }
    }
}
```

- [ ] **Step 2: Create AppContainer.kt**

```kotlin
package com.sunbeat.sshclient.di

import android.content.Context
import com.sunbeat.sshclient.data.local.AppDatabase
import com.sunbeat.sshclient.data.preferences.AppPreferences
import com.sunbeat.sshclient.domain.repository.SessionRepository

class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.getInstance(context)
    val sessionDao = database.sessionDao()
    val sessionRepository = SessionRepository(sessionDao)
    val preferences = AppPreferences(context)
}
```

- [ ] **Step 3: Create SshApp.kt**

```kotlin
package com.sunbeat.sshclient

import android.app.Application
import com.sunbeat.sshclient.di.AppContainer

class SshApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/data/preferences/
git add app/src/main/java/com/sunbeat/sshclient/di/
git add app/src/main/java/com/sunbeat/sshclient/SshApp.kt
git commit -m "feat: add AppPreferences (DataStore), DI container, Application class"
```

---

### Task 7: ConnPool + TerminalSession

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/ssh/ConnPool.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/domain/ssh/TerminalSession.kt`

**Consumes:** Session domain model (Task 2), sshlib, terminal-view

- [ ] **Step 1: Create TerminalSession.kt**

```kotlin
package com.sunbeat.sshclient.domain.ssh

import com.sunbeat.sshclient.data.local.SessionEntity
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession as TermuxTerminalSession
import java.io.InputStream
import java.io.OutputStream

class TerminalSession(
    val dbSession: SessionEntity,
    private val sshClient: Any,       // sshlib SSHClient — exact type TBD at integration
    private val ptyInputStream: InputStream,
    private val ptyOutputStream: OutputStream,
) {

    val id: Long get() = dbSession.id

    // Termux terminal emulator + screen buffer
    val termuxSession: TermuxTerminalSession = TermuxTerminalSession(
        terminalType = dbSession.terminalType,
        rows = dbSession.rows,
        cols = dbSession.cols,
        scrollbackLines = 32000,
        inputStream = ptyInputStream,
        outputStream = ptyOutputStream,
    )

    val emulator: TerminalEmulator get() = termuxSession.emulator

    fun write(data: ByteArray) {
        ptyOutputStream.write(data)
        ptyOutputStream.flush()
    }

    fun write(text: String) {
        write(text.toByteArray())
    }

    fun resize(rows: Int, cols: Int) {
        termuxSession.updateSize(cols, rows)
        // Also notify SSH server of window change via sshClient
    }

    fun close() {
        try { ptyOutputStream.close() } catch (_: Exception) {}
        try { ptyInputStream.close() } catch (_: Exception) {}
        termuxSession.finishIfRunning()
    }
}
```

- [ ] **Step 2: Create ConnPool.kt**

```kotlin
package com.sunbeat.sshclient.domain.ssh

import com.sunbeat.sshclient.data.local.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnPool {

    private val sessions = mutableMapOf<Long, TerminalSession>()
    private val _activeIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeIds: StateFlow<Set<Long>> = _activeIds.asStateFlow()

    fun get(id: Long): TerminalSession? = sessions[id]

    fun isConnected(id: Long): Boolean = id in sessions

    fun add(session: TerminalSession) {
        sessions[session.id] = session
        _activeIds.value = sessions.keys.toSet()
    }

    fun remove(id: Long) {
        sessions[id]?.close()
        sessions.remove(id)
        _activeIds.value = sessions.keys.toSet()
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        _activeIds.value = emptySet()
    }

    fun activeCount(): Int = sessions.size
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/domain/ssh/
git commit -m "feat: add ConnPool and TerminalSession — SSH+terminal session lifecycle"
```

---

### Task 8: HomeViewModel + HomeScreen

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/components/ConnectionCard.kt`

**Consumes:** SessionRepository (Task 5), ConnPool (Task 7)

- [ ] **Step 1: Create HomeViewModel.kt**

```kotlin
package com.sunbeat.sshclient.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.domain.repository.SessionRepository
import com.sunbeat.sshclient.domain.ssh.ConnPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val sessions: List<Session> = emptyList(),
    val groups: List<String> = emptyList(),
    val selectedGroup: String = "",
    val searchQuery: String = "",
    val activeConnections: Set<Long> = emptySet(),
)

class HomeViewModel(
    private val repository: SessionRepository,
    private val connPool: ConnPool,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val allSessions: StateFlow<List<Session>> = repository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.getAllGroups().collect { groups ->
                _uiState.value = _uiState.value.copy(groups = groups)
            }
        }
        viewModelScope.launch {
            connPool.activeIds.collect { ids ->
                _uiState.value = _uiState.value.copy(activeConnections = ids)
            }
        }
    }

    fun selectGroup(group: String) {
        _uiState.value = _uiState.value.copy(selectedGroup = group)
        viewModelScope.launch {
            repository.getSessionsByGroup(group).collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions)
            }
        }
    }

    fun onSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun importFromDirectory(path: String) {
        viewModelScope.launch {
            repository.importFromDirectory(path)
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            repository.delete(session)
        }
    }

    class Factory(
        private val repository: SessionRepository,
        private val connPool: ConnPool,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, connPool) as T
        }
    }
}
```

- [ ] **Step 2: Create ConnectionCard.kt**

```kotlin
package com.sunbeat.sshclient.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunbeat.sshclient.domain.model.Session

@Composable
fun ConnectionCard(
    session: Session,
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "${session.username}@${session.hostname}:${session.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.isFavorite) {
                    Text(
                        text = "★",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isConnected) {
                Icon(
                    painter = painterResource(id = android.R.drawable.presence_online),
                    contentDescription = "Connected",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text("ON", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Preview
@Composable
private fun ConnectionCardPreview() {
    MaterialTheme {
        ConnectionCard(
            session = Session(name = "生产服务器", hostname = "prod.example.com", port = 22, username = "root"),
            isConnected = true,
            onClick = {},
        )
    }
}
```

- [ ] **Step 3: Create HomeScreen.kt**

```kotlin
package com.sunbeat.sshclient.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.ui.components.ConnectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSessionClick: (Session) -> Unit,
    onImportClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var importPath by remember { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Groups",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("All Sessions") },
                    selected = uiState.selectedGroup.isEmpty(),
                    onClick = {
                        viewModel.selectGroup("")
                        scope.launch { drawerState.close() }
                    },
                )
                uiState.groups.forEach { group ->
                    NavigationDrawerItem(
                        label = { Text(group) },
                        selected = uiState.selectedGroup == group,
                        onClick = {
                            viewModel.selectGroup(group)
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(uiState.selectedGroup.ifEmpty { "All Sessions" })
                    },
                    navigationIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Groups")
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onImportClick) {
                    Text("+")
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search connections…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )

                val filteredSessions = uiState.sessions.filter { s ->
                    uiState.searchQuery.isEmpty() ||
                        s.name.contains(uiState.searchQuery, ignoreCase = true) ||
                        s.hostname.contains(uiState.searchQuery, ignoreCase = true)
                }

                if (filteredSessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            "No connections. Tap + to import SCRT sessions.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn {
                        items(filteredSessions, key = { it.id }) { session ->
                            ConnectionCard(
                                session = session,
                                isConnected = session.id in uiState.activeConnections,
                                onClick = { onSessionClick(session) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/ui/home/
git add app/src/main/java/com/sunbeat/sshclient/ui/components/ConnectionCard.kt
git commit -m "feat: add HomeViewModel and HomeScreen with group drawer, search, connection list"
```

---

### Task 9: TerminalViewModel + TerminalScreen

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/terminal/TerminalViewModel.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/terminal/TerminalScreen.kt`

**Consumes:** ConnPool + TerminalSession (Task 7), SessionRepository (Task 5)

- [ ] **Step 1: Create TerminalViewModel.kt**

```kotlin
package com.sunbeat.sshclient.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.domain.repository.SessionRepository
import com.sunbeat.sshclient.domain.ssh.ConnPool
import com.sunbeat.sshclient.domain.ssh.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var terminalSession: TerminalSession? = null

    init {
        viewModelScope.launch {
            val session = repository.getById(sessionId)
            if (session != null) {
                _uiState.value = _uiState.value.copy(sessionName = session.name)
                connect(session)
            }
        }
    }

    fun connect(session: Session) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connecting)
            try {
                // SSH connection will be established via sshlib in a background thread.
                // This is a placeholder for the actual sshlib integration.
                // The TerminalScreen will observe connectionState and render accordingly.
                //
                // Integration outline:
                // 1. Create SSHClient with hostname, port
                // 2. Configure ciphers, kex, host key algorithms from session
                // 3. Authenticate (password or key)
                // 4. Open PTY session → get InputStream/OutputStream
                // 5. Create TerminalSession wrapping sshlib client + termux session
                // 6. connPool.add(terminalSession)
                //
                withContext(Dispatchers.IO) {
                    // TODO: actual sshlib connection in Task 11
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun disconnect() {
        terminalSession?.let { connPool.remove(it.id) }
        terminalSession = null
        _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Disconnected)
    }

    fun reconnect() {
        viewModelScope.launch {
            val session = repository.getById(sessionId)
            if (session != null) connect(session)
        }
    }

    override fun onCleared() {
        super.onCleared()
        terminalSession?.let { connPool.remove(it.id) }
    }

    class Factory(
        private val sessionId: Long,
        private val repository: SessionRepository,
        private val connPool: ConnPool,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(sessionId, repository, connPool) as T
        }
    }
}
```

- [ ] **Step 2: Create TerminalScreen.kt**

```kotlin
package com.sunbeat.sshclient.ui.terminal

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession as TermuxSession
import com.termux.view.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val terminalView = remember {
        TerminalView(context).apply {
            setBackgroundColor(0xFF1E1E1E.toInt())
            setTextColor(0xFFD4D4D4.toInt())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // TerminalView cleanup handled by ViewModel onCleared
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.sessionName) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (uiState.connectionState) {
                is ConnectionState.Disconnected -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Disconnected", style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { viewModel.reconnect() }) {
                                Text("Reconnect")
                            }
                        }
                    }
                }

                is ConnectionState.Connecting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Connecting…", modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                }

                is ConnectionState.Connected -> {
                    AndroidView(
                        factory = { terminalView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is ConnectionState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                (uiState.connectionState as ConnectionState.Error).message,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { viewModel.reconnect() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/ui/terminal/
git commit -m "feat: add TerminalViewModel and TerminalScreen with connection states"
```

---

### Task 10: Navigation + MainActivity + Theme

**Files:**
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/sunbeat/sshclient/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/sunbeat/sshclient/MainActivity.kt` (stub from Task 1)

**Consumes:** HomeViewModel/HomeScreen (Task 8), TerminalViewModel/TerminalScreen (Task 9), AppContainer (Task 6)

- [ ] **Step 1: Create Theme.kt**

```kotlin
package com.sunbeat.sshclient.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF90CAF9),
    secondary = androidx.compose.ui.graphics.Color(0xFF80CBC4),
    background = androidx.compose.ui.graphics.Color(0xFF121212),
    surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2D2D2D),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
)

// For P1, dark theme only. Light theme added in P2.
@Composable
fun SshClientTheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
```

- [ ] **Step 2: Update AppContainer.kt** — add ConnPool since Tasks 7-9 depend on it:

```kotlin
// In AppContainer.kt, add import and field:
import com.sunbeat.sshclient.domain.ssh.ConnPool

// Inside the class body:
val connPool = ConnPool()
```

- [ ] **Step 3: Create NavGraph.kt**

```kotlin
package com.sunbeat.sshclient.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sunbeat.sshclient.di.AppContainer
import com.sunbeat.sshclient.ui.home.HomeScreen
import com.sunbeat.sshclient.ui.home.HomeViewModel
import com.sunbeat.sshclient.ui.terminal.TerminalScreen
import com.sunbeat.sshclient.ui.terminal.TerminalViewModel

object Routes {
    const val HOME = "home"
    const val TERMINAL = "terminal/{sessionId}"
    fun terminal(sessionId: Long) = "terminal/$sessionId"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    container: AppContainer,
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(container.sessionRepository, container.connPool)
            )
            HomeScreen(
                viewModel = vm,
                onSessionClick = { session ->
                    navController.navigate(Routes.terminal(session.id))
                },
                onImportClick = { /* full import wizard in P4 */ },
            )
        }

        composable(
            route = Routes.TERMINAL,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
            val vm: TerminalViewModel = viewModel(
                key = "terminal_$sessionId",
                factory = TerminalViewModel.Factory(sessionId, container.sessionRepository, container.connPool),
            )
            TerminalScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
```

- [ ] **Step 4: Create/update MainActivity.kt**

```kotlin
package com.sunbeat.sshclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sunbeat.sshclient.ui.navigation.NavGraph
import com.sunbeat.sshclient.ui.theme.SshClientTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SshApp
        val container = app.container

        setContent {
            SshClientTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController, container = container)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            (application as SshApp).container.connPool.closeAll()
        }
    }
}
```

- [ ] **Step 5: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/ui/theme/Theme.kt
git add app/src/main/java/com/sunbeat/sshclient/ui/navigation/NavGraph.kt
git add app/src/main/java/com/sunbeat/sshclient/MainActivity.kt
- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/ui/theme/Theme.kt
git add app/src/main/java/com/sunbeat/sshclient/ui/navigation/NavGraph.kt
git add app/src/main/java/com/sunbeat/sshclient/MainActivity.kt
git add app/src/main/java/com/sunbeat/sshclient/di/AppContainer.kt
git commit -m "feat: add navigation, MainActivity, dark theme — P1 core pipeline complete"
```

---

## P1 Completion Check

At this point, the app compiles, shows a home screen with connection list (empty initially), allows importing SCRT .ini files from a directory, and navigating to a terminal screen. The SSH connection glue (sshlib ↔ Termux TerminalSession) is structurally in place but needs the final integration pass (Task 11).

### Task 11 (Integration): Wire sshlib Connection

**Files:**
- Modify: `app/src/main/java/com/sunbeat/sshclient/ui/terminal/TerminalViewModel.kt`

This task replaces the `connect()` placeholder with actual sshlib connection code. The exact API depends on which sshlib version is resolved — the steps below use the ConnectBot sshlib public API pattern.

- [ ] **Step 1: Implement sshlib connection in TerminalViewModel.connect()**

```kotlin
import org.connectbot.sshlib.SSHClient
import org.connectbot.sshlib.SSHSession
import org.connectbot.sshlib.auth.AuthenticationManager
import org.connectbot.sshlib.auth.PasswordAuthenticator
import org.connectbot.sshlib.auth.PublicKeyAuthenticator
import org.connectbot.sshlib.common.ServerHostKeyVerifier
import java.security.PublicKey

// Inside TerminalViewModel, replace the connect() method:

fun connect(session: Session) {
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connecting)
        try {
            withContext(Dispatchers.IO) {
                val client = SSHClient(
                    hostname = session.hostname,
                    port = session.port,
                    serverHostKeyVerifier = object : ServerHostKeyVerifier {
                        override fun verifyHost(host: String, port: Int, key: PublicKey): Boolean {
                            // For P1: accept on first use (trust on first use)
                            // P2+: add known_hosts management
                            return true
                        }
                    },
                )

                // Authenticate
                val authManager = client.authManager
                when (session.authType) {
                    AuthType.PASSWORD, AuthType.BOTH -> {
                        val password = session.plainPassword
                            ?: session.encryptedPassword
                            ?: throw IllegalStateException("No password available")
                        authManager.addAuthenticator(PasswordAuthenticator(password))
                    }
                    AuthType.KEY -> {
                        if (session.identityFile != null) {
                            val keyBytes = java.io.File(session.identityFile).readBytes()
                            authManager.addAuthenticator(PublicKeyAuthenticator(keyBytes))
                        }
                    }
                }
                client.connect()

                // Open PTY session
                val pty = client.openPTY(
                    terminalType = session.terminalType,
                    rows = session.rows,
                    cols = session.cols,
                )

                // Create TerminalSession bridging sshlib → Termux
                val dbEntity = repository.getById(sessionId)
                    ?: throw IllegalStateException("Session not found in DB")

                val termSession = TerminalSession(
                    dbSession = dbEntity,
                    sshClient = client,
                    ptyInputStream = pty.inputStream,
                    ptyOutputStream = pty.outputStream,
                )

                connPool.add(termSession)
                terminalSession = termSession
                _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connected)
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Error(e.message ?: "Connection failed")
            )
        }
    }
}
```

- [ ] **Step 2: Verify integration compiles**

Run: `./gradlew assembleDebug`
Expected: if sshlib API matches → BUILD SUCCESSFUL; if not → adjust API calls to match resolved sshlib version

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/sunbeat/sshclient/ui/terminal/TerminalViewModel.kt
git commit -m "feat: wire sshlib connection in TerminalViewModel — SSH auth + PTY + TerminalSession"
```

---

## P1 Deliverable Summary

After Task 11, the app achieves the P1 milestone:
1. Parse SCRT `.ini` files into Session models
2. Store sessions in Room DB
3. Display connection list with group filtering and search
4. Tap a connection → open TerminalScreen
5. Establish SSH connection with password or key auth
6. Render terminal output via Termux TerminalView
7. Send keyboard input to the remote host

The pipeline "tap connection → working terminal" is fully functional.

---

## Implementation Status (as of 2026-06-22)

P1 core pipeline is **complete** with the following divergences from plan:

| Plan | Actual | Reason |
|------|--------|--------|
| `com.github.connectbot:sshlib:2.2.1` | `com.trilead.ssh2` (JAR `2.2.46`) | JitPack version not found, JAR placed in `libs/` |
| `com.termux:terminal-view:0.2` | AAR `0.118.0` in `libs/` | JitPack returns HTTP 401 for Gradle remote resolution |
| `SSHClient` / `openPTY()` | `Connection` / `openSession()` → `requestPTY()` → `startShell()` | sshlib API is trilead SSH2, not ConnectBot wrapper |
| `TermuxTerminalSession` for SSH | `TerminalEmulator` directly + dummy `TermuxTerminalSession` + `mEmulator` swap | TermuxTerminalSession binds to local PTY, unsuitable for SSH |
| ConnPool plain `mutableMapOf` | `Mutex`-guarded mutable map | Thread safety found missing in review |
| HomeViewModel `stateIn` + manual collect | `flatMapLatest` on `groupFilter` StateFlow | All Sessions filter bug: `getSessionsByGroup("")` only returned empty-group sessions |
| FAB triggers `onImportClick` | FAB opens `AlertDialog` manual add-session form | User feedback: expected manual add, not re-import |
| Flat session list | Multi-level nested folder tree with depth indentation + collapse/expand all | 577 sessions across 30+ nested SCRT folders unbrowsable without hierarchy |
| No dedup on import | Dedup by `hostname:port:username`, keep password entries | Duplicate entries in bundled JSON |
| No edit/delete for sessions | Long-press edit dialog (hostname/port/user/auth/pwd/key/name) + secondary delete confirm | User feedback: need to modify and delete sessions from UI |
| Password-only auth in add dialog | AuthType selector (Password/Key/Key+Pwd) with conditional fields | Key and dual-auth are standard SSH auth modes |

**Bug fixes:**
- SCRT port import: `data.get('Port')` → `data.get('[SSH2] Port')` — INI key includes section prefix with brackets (e.g. `000071a7` → 29095)
- Version-tracked JSON re-import: `BUNDLED_JSON_VERSION` bump forces deleteAll + reimport on app upgrade
- Session dedup fix: 577 unique instead of 700+ duplicates

**Key post-plan additions:**
- JSON asset import on first launch (`sessions_export.json` in `assets/`)
- `appContext` param in `AppContainer` and `HomeViewModel.Factory`
- `deleteAll()`, `delete()`, `update()` in SessionRepository for session management
- `.superpowers/sdd/progress.md` ledger tracking all completed tasks and bugfixes
- `开发经验.md` development journal (19 chapters, 650+ lines)
- Auto-deploy pipeline: bump version → rebuild → scp APK+version.json to sunbeatus
- Update check/download/install via UpdateManager + FileProvider

**Commits:** 17 commits covering Tasks 1-11 + 6 bugfix/ux commits.
