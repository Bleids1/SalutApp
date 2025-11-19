package com.example.salutapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SageGreen,       // Verde mais claro para destaque no escuro
    secondary = ForestGreen,
    background = Color(0xFF1B1C1B), // Fundo escuro, quase preto
    surface = Color(0xFF242624),    // Superfície um pouco mais clara
    onPrimary = OrganicWhite,
    onSecondary = OrganicWhite,
    onBackground = OrganicWhite,
    onSurface = OrganicWhite
)

private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,    // Verde escuro como cor principal
    secondary = SageGreen,
    background = OrganicWhite, // Fundo branco orgânico
    surface = OrganicWhite,
    onPrimary = White,        // Texto branco sobre o verde escuro
    onSecondary = White,
    onBackground = DarkText,  // Texto escuro sobre o fundo branco
    onSurface = DarkText
)

@Composable
fun SalutAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is not used to keep the brand identity
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}