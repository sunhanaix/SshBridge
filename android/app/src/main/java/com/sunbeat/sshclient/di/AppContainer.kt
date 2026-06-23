package com.sunbeat.sshclient.di

import android.content.Context
import com.sunbeat.sshclient.data.local.AppDatabase
import com.sunbeat.sshclient.data.preferences.AppPreferences
import com.sunbeat.sshclient.domain.repository.SessionRepository
import com.sunbeat.sshclient.domain.ssh.ConnPool

class AppContainer(val appContext: Context) {

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val sessionDao = database.sessionDao()
    val sessionRepository = SessionRepository(sessionDao)
    val preferences = AppPreferences(appContext)
    val connPool = ConnPool()
}
