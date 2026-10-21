package com.bilitv.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme
import androidx.tv.material3.MaterialTheme as TvTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

val Pink = Color(0xFFFB7299)
val Bg = Color(0xFF0F0F13)
val CardBg = Color(0xFF1C1C22)
val CardFocusBg = Color(0xFF2A2A33)
val Gray = Color(0xFFA0A0AA)

@Composable
fun BiliTheme(content: @Composable () -> Unit) {
    TvTheme(
        colorScheme = tvDarkColorScheme(
            primary = Pink,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Color.White,
            surface = CardBg,
            onSurface = Color.White,
        )
    ) {
        M3Theme(
            colorScheme = m3DarkColorScheme(
                primary = Pink,
                onPrimary = Color.White,
                background = Bg,
                surface = CardBg,
            )
        ) {
            CompositionLocalProvider(
                androidx.tv.material3.LocalContentColor provides Color.White,
                androidx.compose.material3.LocalContentColor provides Color.White,
                content = content,
            )
        }
    }
}
