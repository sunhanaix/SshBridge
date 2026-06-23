package com.sunbeat.sshclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.sunbeat.sshclient.ui.navigation.NavGraph
import com.sunbeat.sshclient.ui.theme.SshClientTheme
import kotlinx.coroutines.runBlocking

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
            runBlocking {
                (application as SshApp).container.connPool.closeAll()
            }
        }
    }
}
