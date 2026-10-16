package com.doctorcal.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.doctorcal.app.data.Shift

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEF0),
    onPrimaryContainer = Color(0xFF00363B),
    background = Color(0xFFF4F8F9),
    onBackground = Color(0xFF171D1E),
    surface = Color.White,
    onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFE3ECEE),
    onSurfaceVariant = Color(0xFF3F4A4C),
    outline = Color(0xFF6F797B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF59D2DC),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F55),
    onPrimaryContainer = Color(0xFFCDEEF0),
    background = Color(0xFF0F1415),
    onBackground = Color(0xFFDDE4E5),
    surface = Color(0xFF171E1F),
    onSurface = Color(0xFFDDE4E5),
    surfaceVariant = Color(0xFF2A3335),
    onSurfaceVariant = Color(0xFFBFC8CA),
    outline = Color(0xFF899294),
)

@Composable
fun DoctorCalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** Accent colour of a shift: warm amber for the morning, indigo for the evening. */
@Composable
fun shiftAccent(shift: Shift): Color {
    val dark = isSystemInDarkTheme()
    return when (shift) {
        Shift.MORNING -> if (dark) Color(0xFFFFC857) else Color(0xFFC77D00)
        Shift.EVENING -> if (dark) Color(0xFFA5ADFF) else Color(0xFF4F5BB5)
    }
}
