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
