package com.remodex.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import com.remodex.android.R

private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

private val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF111316),
    onPrimary = Color(0xFFF7F5F0),
    primaryContainer = Color(0xFFE8E4DB),
    secondary = Color(0xFF3D5B4E),
    background = Color(0xFFF4F1EA),
    surface = Color(0xFFFBF8F3),
    surfaceVariant = Color(0xFFE8E4DB),
    onSurface = Color(0xFF15171A),
    onSurfaceVariant = Color(0xFF5B5F66),
    outline = Color(0xFFD4CEC1),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFECE9E1),
    onPrimary = Color(0xFF101214),
    secondary = Color(0xFF9CC7AE),
    background = Color(0xFF101214),
    surface = Color(0xFF15181B),
    surfaceVariant = Color(0xFF202429),
    onSurface = Color(0xFFECE9E1),
    onSurfaceVariant = Color(0xFFB9B3A8),
    outline = Color(0xFF41464C),
    error = Color(0xFFFFB4AB),
)

private val RemodexTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 34.sp),
    headlineMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun RemodexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RemodexTypography,
        content = content,
    )
}
