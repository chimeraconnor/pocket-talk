package com.pockettalk.app.ui.home

import androidx.compose.runtime.Composable

@Composable
fun SettingsDialog(
  onDismissed: () -> Unit,
  onThemeChanged: (String) -> Unit = {},
) {
  onDismissed()
}
