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
