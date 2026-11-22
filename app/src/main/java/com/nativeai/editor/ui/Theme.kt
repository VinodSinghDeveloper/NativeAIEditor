package com.nativeai.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun Theme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Color(0xFF9C7CFF),
      secondary = Color(0xFF64DFDF),
      surface = Color(0xFF17171D),
      background = Color(0xFF101014),
      surfaceVariant = Color(0xFF24242C)
    ),
    content = content
  )
}
