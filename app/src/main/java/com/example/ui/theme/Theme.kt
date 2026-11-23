package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VaultColorScheme = darkColorScheme(
    primary = EmeraldPulse,
    onPrimary = ObsidianBlack,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldPulse,
    secondary = CyanDrive,
    onSecondary = ObsidianBlack,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = CyanDrive,
    tertiary = AmberMotion,
    onTertiary = ObsidianBlack,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberMotion,
    error = CrimsonLive,
    onError = ObsidianBlack,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonLive,
    background = VaultBg,
    onBackground = TextPrimary,
    surface = VaultSurface,
    onSurface = TextPrimary,
    surfaceVariant = VaultSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = VaultOutline,
    outlineVariant = VaultOutline
)

private val VaultShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VaultRecTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VaultColorScheme,
        typography = VaultTypography,
        shapes = VaultShapes,
        content = content
    )
}
