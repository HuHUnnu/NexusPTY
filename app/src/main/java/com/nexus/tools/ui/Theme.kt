package com.nexus.tools.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Cyan = Color(0xFF06D6A0)
val CyanDim = Color(0xFF059669)
val CyanBright = Color(0xFF34D399)

val Purple = Color(0xFF8B5CF6)
val PurpleLight = Color(0xFFA78BFA)

val Coral = Color(0xFFFF6B6B)
val Amber = Color(0xFFFBBF24)
val Blue = Color(0xFF60A5FA)
val Green = Color(0xFF4ADE80)
val Red = Color(0xFFF87171)

val SurfaceDark = Color(0xFF0A0A0F)
val SurfaceCard = Color(0xFF12121A)
val SurfaceElevated = Color(0xFF1A1A24)
val Border = Color(0xFF2A2A38)
val TextPrimary = Color(0xFFF0F0F5)
val TextSecondary = Color(0xFF8888A0)
val TextDim = Color(0xFF55556A)

private val DarkScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF003822),
    primaryContainer = CyanDim,
    secondary = Purple,
    secondaryContainer = Color(0xFF3B2580),
    background = SurfaceDark,
    surface = SurfaceCard,
    surfaceVariant = SurfaceElevated,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    outlineVariant = Color(0xFF1E1E2A),
    error = Coral,
    onError = Color.White,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        color = TextPrimary
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = TextSecondary
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = TextSecondary
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        fontFamily = FontFamily.Monospace,
        color = TextDim
    )
)

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content
    )
}
