package dev.pranav.reef.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.pranav.reef.App

// ─── Reef ocean palette — used on pre-Android-12 devices (no dynamic color) ───
//
// Inspired by deep ocean / coral reef: rich teals, warm corals, sandy golds.
// Dark mode: bioluminescent deep sea.  Light mode: sun-dappled surface water.

// ── Dark ────────────────────────────────────────────────────────────────────────
private val md_dark_primary              = Color(0xFF4DD0D0)
private val md_dark_onPrimary            = Color(0xFF002020)
private val md_dark_primaryContainer     = Color(0xFF004F4F)
private val md_dark_onPrimaryContainer   = Color(0xFFA0EDED)
private val md_dark_secondary            = Color(0xFFFF9171)
private val md_dark_onSecondary          = Color(0xFF1E0500)
private val md_dark_secondaryContainer   = Color(0xFF5C2200)
private val md_dark_onSecondaryContainer = Color(0xFFFFBDA0)
private val md_dark_tertiary             = Color(0xFFD4BC78)
private val md_dark_onTertiary           = Color(0xFF1E1100)
private val md_dark_tertiaryContainer    = Color(0xFF4A3700)
private val md_dark_onTertiaryContainer  = Color(0xFFF2D99A)
private val md_dark_error                = Color(0xFFFFB4AB)
private val md_dark_onError              = Color(0xFF690005)
private val md_dark_errorContainer       = Color(0xFF93000A)
private val md_dark_onErrorContainer     = Color(0xFFFFDAD6)
private val md_dark_background           = Color(0xFF0A1616)
private val md_dark_onBackground         = Color(0xFFDEF7F7)
private val md_dark_surface              = Color(0xFF0A1616)
private val md_dark_onSurface            = Color(0xFFDEF7F7)
private val md_dark_surfaceVariant       = Color(0xFF1C2D2D)
private val md_dark_onSurfaceVariant     = Color(0xFFAACCCC)
private val md_dark_surfaceContainer     = Color(0xFF131F1F)
private val md_dark_surfaceContainerHigh = Color(0xFF1A2B2B)
private val md_dark_surfaceContainerLow  = Color(0xFF0E1A1A)
private val md_dark_outline              = Color(0xFF507070)
private val md_dark_outlineVariant       = Color(0xFF2A4040)
private val md_dark_inverseSurface       = Color(0xFFDEF7F7)
private val md_dark_inverseOnSurface     = Color(0xFF001F1F)
private val md_dark_inversePrimary       = Color(0xFF006969)

// ── Light ───────────────────────────────────────────────────────────────────────
private val md_light_primary              = Color(0xFF006868)
private val md_light_onPrimary            = Color(0xFFFFFFFF)
private val md_light_primaryContainer     = Color(0xFF9EEEEE)
private val md_light_onPrimaryContainer   = Color(0xFF002020)
private val md_light_secondary            = Color(0xFFA83D00)
private val md_light_onSecondary          = Color(0xFFFFFFFF)
private val md_light_secondaryContainer   = Color(0xFFFFDBCA)
private val md_light_onSecondaryContainer = Color(0xFF3B0D00)
private val md_light_tertiary             = Color(0xFF6C5C00)
private val md_light_onTertiary           = Color(0xFFFFFFFF)
private val md_light_tertiaryContainer    = Color(0xFFFBE194)
private val md_light_onTertiaryContainer  = Color(0xFF211B00)
private val md_light_error                = Color(0xFFBA1A1A)
private val md_light_onError              = Color(0xFFFFFFFF)
private val md_light_errorContainer       = Color(0xFFFFDAD6)
private val md_light_onErrorContainer     = Color(0xFF410002)
private val md_light_background           = Color(0xFFF3FAFA)
private val md_light_onBackground         = Color(0xFF001F1F)
private val md_light_surface              = Color(0xFFF3FAFA)
private val md_light_onSurface            = Color(0xFF001F1F)
private val md_light_surfaceVariant       = Color(0xFFDAE5E5)
private val md_light_onSurfaceVariant     = Color(0xFF3E4F4F)
private val md_light_surfaceContainer     = Color(0xFFE7F3F3)
private val md_light_surfaceContainerHigh = Color(0xFFDCEDED)
private val md_light_surfaceContainerLow  = Color(0xFFEEF8F8)
private val md_light_outline              = Color(0xFF6E8080)
private val md_light_outlineVariant       = Color(0xFFBECCCC)
private val md_light_inverseSurface       = Color(0xFF001F1F)
private val md_light_inverseOnSurface     = Color(0xFFDEF7F7)
private val md_light_inversePrimary       = Color(0xFF4DD0D0)

private val ReefDarkColorScheme = darkColorScheme(
    primary              = md_dark_primary,
    onPrimary            = md_dark_onPrimary,
    primaryContainer     = md_dark_primaryContainer,
    onPrimaryContainer   = md_dark_onPrimaryContainer,
    secondary            = md_dark_secondary,
    onSecondary          = md_dark_onSecondary,
    secondaryContainer   = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary             = md_dark_tertiary,
    onTertiary           = md_dark_onTertiary,
    tertiaryContainer    = md_dark_tertiaryContainer,
    onTertiaryContainer  = md_dark_onTertiaryContainer,
    error                = md_dark_error,
    onError              = md_dark_onError,
    errorContainer       = md_dark_errorContainer,
    onErrorContainer     = md_dark_onErrorContainer,
    background           = md_dark_background,
    onBackground         = md_dark_onBackground,
    surface              = md_dark_surface,
    onSurface            = md_dark_onSurface,
    surfaceVariant       = md_dark_surfaceVariant,
    onSurfaceVariant     = md_dark_onSurfaceVariant,
    surfaceContainer     = md_dark_surfaceContainer,
    surfaceContainerHigh = md_dark_surfaceContainerHigh,
    surfaceContainerLow  = md_dark_surfaceContainerLow,
    outline              = md_dark_outline,
    outlineVariant       = md_dark_outlineVariant,
    inverseSurface       = md_dark_inverseSurface,
    inverseOnSurface     = md_dark_inverseOnSurface,
    inversePrimary       = md_dark_inversePrimary,
)

private val ReefLightColorScheme = lightColorScheme(
    primary              = md_light_primary,
    onPrimary            = md_light_onPrimary,
    primaryContainer     = md_light_primaryContainer,
    onPrimaryContainer   = md_light_onPrimaryContainer,
    secondary            = md_light_secondary,
    onSecondary          = md_light_onSecondary,
    secondaryContainer   = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary             = md_light_tertiary,
    onTertiary           = md_light_onTertiary,
    tertiaryContainer    = md_light_tertiaryContainer,
    onTertiaryContainer  = md_light_onTertiaryContainer,
    error                = md_light_error,
    onError              = md_light_onError,
    errorContainer       = md_light_errorContainer,
    onErrorContainer     = md_light_onErrorContainer,
    background           = md_light_background,
    onBackground         = md_light_onBackground,
    surface              = md_light_surface,
    onSurface            = md_light_onSurface,
    surfaceVariant       = md_light_surfaceVariant,
    onSurfaceVariant     = md_light_onSurfaceVariant,
    surfaceContainer     = md_light_surfaceContainer,
    surfaceContainerHigh = md_light_surfaceContainerHigh,
    surfaceContainerLow  = md_light_surfaceContainerLow,
    outline              = md_light_outline,
    outlineVariant       = md_light_outlineVariant,
    inverseSurface       = md_light_inverseSurface,
    inverseOnSurface     = md_light_inverseOnSurface,
    inversePrimary       = md_light_inversePrimary,
)

/**
 * Reef shape scale — deliberately generous rounding for a friendly, modern feel.
 * extraSmall = chip corners; large/extraLarge = card / sheet / dialog corners.
 */
private val ReefShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReefTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else           dynamicLightColorScheme(context)
        }
        darkTheme -> ReefDarkColorScheme
        else      -> ReefLightColorScheme
    }

    App.colorScheme = colorScheme

    // Apply status bar colour to match surface — Material You immersive
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? android.app.Activity)?.window
        window?.let {
            it.statusBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography  = ReefTypography,
        shapes      = ReefShapes,
        content     = content
    )
}
