package dev.mikadore.nuntius.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GruvboxDarkColorScheme = darkColorScheme(
    primary = GruvboxYellow,
    onPrimary = GruvboxBackground,
    primaryContainer = GruvboxSurfaceVariant,
    onPrimaryContainer = GruvboxForeground,
    secondary = GruvboxBlue,
    onSecondary = GruvboxBackground,
    secondaryContainer = GruvboxSurfaceVariant,
    onSecondaryContainer = GruvboxForeground,
    tertiary = GruvboxPurple,
    onTertiary = GruvboxBackground,
    tertiaryContainer = GruvboxSurfaceVariant,
    onTertiaryContainer = GruvboxForeground,
    background = GruvboxBackground,
    onBackground = GruvboxForeground,
    surface = GruvboxSurface,
    onSurface = GruvboxForeground,
    surfaceVariant = GruvboxSurfaceVariant,
    onSurfaceVariant = GruvboxMuted,
    inverseSurface = GruvboxForeground,
    inverseOnSurface = GruvboxBackground,
    inversePrimary = GruvboxOrangeDark,
    error = GruvboxRed,
    onError = GruvboxBackground,
    errorContainer = GruvboxErrorContainer,
    onErrorContainer = GruvboxForeground,
    outline = GruvboxGray,
    outlineVariant = GruvboxSurfaceVariant,
    scrim = GruvboxBackground
)

@Composable
fun NuntiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GruvboxDarkColorScheme,
        typography = Typography,
        content = content
    )
}
