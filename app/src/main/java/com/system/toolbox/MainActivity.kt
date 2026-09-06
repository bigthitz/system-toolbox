package com.system.toolbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.system.toolbox.core.StoreSession
import com.system.toolbox.ui.AppRoot
import com.system.toolbox.ui.theme.SystemToolboxTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StoreSession.attach(applicationContext)
        enableEdgeToEdge()
        setContent {
            SystemToolboxTheme {
                AppRoot()
            }
        }
    }
}
