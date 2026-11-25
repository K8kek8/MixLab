package com.mixlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mixlab.app.ui.MixScreen
import com.mixlab.app.ui.MixViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFFFF8A65),
                    background = Color(0xFF0E0E14),
                    surface = Color(0xFF0E0E14),
                    surfaceVariant = Color(0xFF1A1A24),
                    surfaceContainerHighest = Color(0xFF1E1E2A),
                ),
            ) {
                val vm: MixViewModel = viewModel()
                MixScreen(vm)
            }
        }
    }
}
