package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// TowerLock "mission control" palette. Every screen in the app is built around
// these fixed dark tones rather than the system's dynamic Material You colors,
// so they're centralized here as the single source of truth.
val TlBackground = Color(0xFF0F172A) // page background (slate-900)
val TlSurface = Color(0xFF1E293B) // cards, sheets, dialogs (slate-800)
val TlSurfaceVariant = Color(0xFF334155) // dividers, secondary surfaces (slate-700)
val TlOutline = Color(0xFF475569) // borders, disabled controls (slate-600)

val TlTextPrimary = Color.White
val TlTextSecondary = Color(0xFF94A3B8) // slate-400
val TlTextMuted = Color(0xFF64748B) // slate-500

val TlEmerald = Color(0xFF10B981) // primary brand accent / "excellent" signal
val TlEmeraldMuted = Color(0xFF8BC34A) // "good" signal
val TlSky = Color(0xFF38BDF8) // info accent (distance, bearing, links)
val TlAmber = Color(0xFFF59E0B) // "fair" signal / warnings
val TlAmberSoft = Color(0xFFFFB74D)
val TlRed = Color(0xFFEF4444) // destructive actions
val TlRedSoft = Color(0xFFE57373) // "poor" signal
val TlPink = Color(0xFFEC4899) // TA ring / distance accent
