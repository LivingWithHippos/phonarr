package com.github.livingwithhippos.phonarr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.livingwithhippos.phonarr.core.data.AppSettings
import com.github.livingwithhippos.phonarr.core.data.ThemeColor
import com.github.livingwithhippos.phonarr.core.data.ThemeMode

private val SquareShapes =
    Shapes(
        extraSmall = RoundedCornerShape(0.dp),
        small = RoundedCornerShape(0.dp),
        medium = RoundedCornerShape(0.dp),
        large = RoundedCornerShape(0.dp),
        extraLarge = RoundedCornerShape(0.dp),
    )

@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

// Pure black surfaces for AMOLED screens; containers stay slightly lighter so cards remain visible.
private fun ColorScheme.toPureBlack(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF1C1C1C),
        surfaceContainerHighest = Color(0xFF262626),
    )

@Composable
fun PhonarrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: ThemeColor = ThemeColor.SYSTEM,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = themeColor == ThemeColor.SYSTEM,
    pureBlack: Boolean = false,
    standardFont: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseColorScheme =
        when {
            dynamicColor && themeColor == ThemeColor.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            else -> themeColor.colorScheme(darkTheme)
        }
    val colorScheme = if (darkTheme && pureBlack) baseColorScheme.toPureBlack() else baseColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (standardFont) StandardTypography else Typography,
        shapes = SquareShapes,
        content = content,
    )
}

/** The theme the user picked in Settings. */
@Composable
fun PhonarrTheme(settings: AppSettings, content: @Composable () -> Unit) =
    PhonarrTheme(
        darkTheme = shouldUseDarkTheme(settings.themeMode),
        themeColor = settings.themeColor,
        pureBlack = settings.themeMode == ThemeMode.AMOLED,
        standardFont = settings.standardFont,
        content = content,
    )
