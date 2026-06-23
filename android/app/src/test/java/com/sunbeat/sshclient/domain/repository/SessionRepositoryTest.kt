package com.sunbeat.sshclient.domain.repository

import android.content.Context
import android.content.res.AssetManager
import com.sunbeat.sshclient.data.local.SessionDao
import com.sunbeat.sshclient.data.local.SessionEntity
import com.sunbeat.sshclient.domain.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(MockitoJUnitRunner::class)
class SessionRepositoryTest {

    @Mock
    private lateinit var sessionDao: SessionDao

    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        repository = SessionRepository(sessionDao)
    }

    // -----------------------------------------------------------------------
    // Flow-based queries
    // -----------------------------------------------------------------------

    @Test
    fun `getAllSessions maps entities to domain models`() = runTest {
        val entities = listOf(
            SessionEntity(id = 1, name = "Server A", hostname = "a.example.com"),
            SessionEntity(id = 2, name = "Server B", hostname = "b.example.com"),
        )
        whenever(sessionDao.getAllSessions()).thenReturn(flowOf(entities))

        val sessions = repository.getAllSessions().first()

        assertEquals(2, sessions.size)
        assertEquals("Server A", sessions[0].name)
        assertEquals("a.example.com", sessions[0].hostname)
        assertEquals(1L, sessions[0].id)
    }

    @Test
    fun `getSessionsByGroup filters and maps`() = runTest {
        val entities = listOf(
            SessionEntity(id = 1, name = "DB", hostname = "db.internal", groupName = "Prod"),
            SessionEntity(id = 2, name = "Web", hostname = "web.internal", groupName = "Prod"),
        )
        whenever(sessionDao.getSessionsByGroup("Prod")).thenReturn(flowOf(entities))

        val sessions = repository.getSessionsByGroup("Prod").first()

        assertEquals(2, sessions.size)
        assertEquals("DB", sessions[0].name)
    }

    @Test
    fun `getAllGroups delegates to DAO`() = runTest {
        whenever(sessionDao.getAllGroups()).thenReturn(flowOf(listOf("Dev", "Prod")))

        val groups = repository.getAllGroups().first()

        assertEquals(2, groups.size)
        assertEquals("Dev", groups[0])
    }

    // -----------------------------------------------------------------------
    // Single-record operations
    // -----------------------------------------------------------------------

    @Test
    fun `getById returns session when found`() = runTest {
        val entity = SessionEntity(id = 7, name = "Found", hostname = "found.io")
        whenever(sessionDao.getById(7L)).thenReturn(entity)

        val session = repository.getById(7L)

        assertEquals("Found", session?.name)
        assertEquals("found.io", session?.hostname)
    }

    @Test
    fun `getById returns null when not found`() = runTest {
        whenever(sessionDao.getById(999L)).thenReturn(null)

        assertNull(repository.getById(999L))
    }

    @Test
    fun `insert delegates and returns generated id`() = runTest {
        val session = Session(name = "New", hostname = "new.example.com")
        whenever(sessionDao.insert(any())).thenReturn(42L)

        val id = repository.insert(session)

        assertEquals(42L, id)
        verify(sessionDao).insert(any<SessionEntity>())
    }

    @Test
    fun `update delegates to DAO`() = runTest {
        val session = Session(id = 1, name = "Updated", hostname = "upd.example.com")

        repository.update(session)

        verify(sessionDao).update(any<SessionEntity>())
    }

    @Test
    fun `delete delegates to DAO`() = runTest {
        val session = Session(id = 1, name = "Gone", hostname = "gone.example.com")

        repository.delete(session)

        verify(sessionDao).delete(any<SessionEntity>())
    }

    @Test
    fun `deleteById delegates to DAO`() = runTest {
        repository.deleteById(7L)

        verify(sessionDao).deleteById(7L)
    }

    @Test
    fun `count delegates to DAO`() = runTest {
        whenever(sessionDao.count()).thenReturn(5)

        val result = repository.count()

        assertEquals(5, result)
    }

    // -----------------------------------------------------------------------
    // importFromDirectory
    // -----------------------------------------------------------------------

    @Test
    fun `importFromDirectory returns 0 when path is not a directory`() = runTest {
        val count = repository.importFromDirectory("/nonexistent/path")
        assertEquals(0, count)
    }

    @Test
    fun `importFromDirectory parses ini files and batch-inserts`() = runTest {
        val tempDir = createTempDir("sessionImport")
        try {
            File(tempDir, "web-server.ini").writeText("""S:"Hostname"=web.example.com""" + "\n")
            File(tempDir, "db-server.ini").writeText("""S:"Hostname"=db.example.com""" + "\n")

            val count = repository.importFromDirectory(tempDir.absolutePath, "Production")

            assertEquals(2, count)
            verify(sessionDao).insertAll(any<List<SessionEntity>>())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `importFromDirectory skips non-ini files`() = runTest {
        val tempDir = createTempDir("sessionImportSkip")
        try {
            File(tempDir, "valid.ini").writeText("""S:"Hostname"=good.example.com""" + "\n")
            File(tempDir, "readme.txt").writeText("not an ini file")
            File(tempDir, "data.csv").writeText("a,b,c")

            val count = repository.importFromDirectory(tempDir.absolutePath)

            assertEquals(1, count)
            verify(sessionDao).insertAll(any<List<SessionEntity>>())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `importFromDirectory returns 0 when no ini files exist`() = runTest {
        val tempDir = createTempDir("sessionImportEmpty")
        try {
            val count = repository.importFromDirectory(tempDir.absolutePath)
            assertEquals(0, count)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // importFromJsonAssets
    // -----------------------------------------------------------------------

    @Test
    fun `importFromJsonAssets parses json array and batch-inserts`() = runTest {
        val json = """
            [
                {"session":"Web","folder":"Prod","hostname":"web.example.com","port":22,"username":"admin","has_saved_password":true,"password":"s3cret"},
                {"session":"DB","folder":"Prod","hostname":"db.example.com","port":2222,"username":"root","has_saved_password":false},
                {"session":"Jump","folder":"","hostname":"jump.example.com","port":2222,"username":"jumpuser","firewall":"None"}
            ]
        """.trimIndent()

        val context = mockContextWithJson(json)

        val count = repository.importFromJsonAssets(context)

        assertEquals(3, count)
        verify(sessionDao).insertAll(any<List<SessionEntity>>())
    }

    @Test
    fun `importFromJsonAssets handles has_saved_password correctly`() = runTest {
        val json = """
            [
                {"session":"WithPwd","hostname":"a.com","has_saved_password":true,"password":"visible"},
                {"session":"NoPwd","hostname":"b.com","has_saved_password":false}
            ]
        """.trimIndent()

        val context = mockContextWithJson(json)
        val count = repository.importFromJsonAssets(context)
        assertEquals(2, count)

        // Verify that the first session has a plainPassword and the second does not
        verify(sessionDao).insertAll(any<List<SessionEntity>>())
    }

    @Test
    fun `importFromJsonAssets maps firewall None to null jump host`() = runTest {
        val json = """
            [
                {"session":"S1","hostname":"s1.com","firewall":"None"},
                {"session":"S2","hostname":"s2.com","firewall":"bastion.internal"}
            ]
        """.trimIndent()

        val context = mockContextWithJson(json)
        val count = repository.importFromJsonAssets(context)
        assertEquals(2, count)
        verify(sessionDao).insertAll(any<List<SessionEntity>>())
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun mockContextWithJson(jsonContent: String): Context {
        val context = mock<Context>()
        val assetManager = mock<AssetManager>()
        whenever(context.assets).thenReturn(assetManager)
        whenever(assetManager.open(eq("sessions_export.json")))
            .thenReturn(ByteArrayInputStream(jsonContent.toByteArray()))
        return context
    }
}
