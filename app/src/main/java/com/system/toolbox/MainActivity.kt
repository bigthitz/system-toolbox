package com.system.toolbox

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.system.toolbox.core.Activation
import com.system.toolbox.core.StoreSession
import com.system.toolbox.ui.AppRoot
import com.system.toolbox.ui.screens.ActivationScreen
import com.system.toolbox.ui.theme.SystemToolboxTheme

class MainActivity : ComponentActivity() {

    /** 读取已保存的激活码并重新校验（换机/换 SN 后自动失效） */
    private fun isActivated(): Boolean {
        val code = getSharedPreferences(Activation.PREFS, Context.MODE_PRIVATE)
            .getString(Activation.KEY_CODE, null) ?: return false
        val sn = Activation.readSn() ?: return false
        return Activation.verify(code, sn)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StoreSession.attach(applicationContext)
        enableEdgeToEdge()
        setContent {
            SystemToolboxTheme {
                var activated by remember { mutableStateOf(isActivated()) }
                if (activated) {
                    AppRoot()
                } else {
                    ActivationScreen(onActivated = { activated = true })
                }
            }
        }
    }
}
