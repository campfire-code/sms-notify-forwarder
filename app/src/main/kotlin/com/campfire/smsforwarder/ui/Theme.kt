package com.campfire.smsforwarder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Fire = Color(0xFFE85A0C)
private val scheme = lightColorScheme(
    primary = Fire,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9DA),
    onPrimaryContainer = Color(0xFF5A2200),
    secondary = Color(0xFF4E5560),
    background = Color(0xFFF6F7F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF0F3),
    surfaceContainerHighest = Color.White,
    surfaceContainer = Color.White,
    error = Color(0xFFC62828),
)

val OkGreen = Color(0xFF2E7D32)
val WarnAmber = Color(0xFFB26A00)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
