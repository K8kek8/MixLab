package com.mixlab.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mixlab.app.ui.MixScreen
import com.mixlab.app.ui.MixViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pantalla completa (las barras reaparecen al deslizar desde el borde) y sin apagarse mientras se mezcla
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF29D3E8),
                    secondary = Color(0xFFFF8A65),
                    background = Color(0xFF0A0A0F),
                    surface = Color(0xFF14141D),
                    surfaceVariant = Color(0xFF1A1A24),
                    onSurface = Color(0xFFEDEDF2),
                ),
            ) {
                val vm: MixViewModel = viewModel()
                MixScreen(vm)
            }
        }
    }
}
