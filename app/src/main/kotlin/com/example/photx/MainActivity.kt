package com.example.photx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.photx.core.designsystem.PhotxTheme
import com.example.photx.navigation.PhotxAppShell
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity Compose entry point for PhotX.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by rememberSaveable(systemDark) { mutableStateOf(systemDark) }
            PhotxTheme(darkTheme = isDarkTheme) {
                Surface(color = PhotxTheme.colorScheme.background) {
                    PhotxAppShell(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = { isDarkTheme = !isDarkTheme }
                    )
                }
            }
        }
    }
}
