package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// TowerLock is a fixed-identity "mission control" dark UI by design — every
// screen already renders against TlBackground/TlSurface regardless of system
// theme. Dynamic Material You color would only reach the handful of stock
// Material components screens don't explicitly style (AlertDialog, default
// Slider/Switch tracks), producing a jarring mismatch against the rest of the
// UI. So this app deliberately has one ColorScheme, not a light/dark pair.
private val TowerLockColorScheme = darkColorScheme(
  primary = TlEmerald,
  onPrimary = TlTextPrimary,
  secondary = TlSky,
  onSecondary = TlTextPrimary,
  tertiary = TlPink,
  onTertiary = TlTextPrimary,
  background = TlBackground,
  onBackground = TlTextPrimary,
  surface = TlSurface,
  onSurface = TlTextPrimary,
  surfaceVariant = TlSurfaceVariant,
  onSurfaceVariant = TlTextSecondary,
  outline = TlOutline,
  error = TlRed,
  onError = TlTextPrimary,
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = TowerLockColorScheme, typography = Typography, content = content)
}
