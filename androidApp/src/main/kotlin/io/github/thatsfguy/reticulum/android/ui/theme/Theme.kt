package io.github.thatsfguy.reticulum.android.ui.theme

/**
 * Design tokens ported from the webclient's css/style.css.
 * Both light and dark palettes are provided so the app matches
 * the webclient's look on both themes.
 *
 * The webclient uses CSS custom properties in :root (light) and
 * [data-theme="dark"]. This file maps those to Material 3 color
 * scheme values for Jetpack Compose.
 *
 * TODO: Implement using MaterialTheme with custom ColorScheme.
 *       See reference file: ../../../reticulum-lora-webclient/css/style.css
 */

// ---- Light palette (from the UI handoff brief) ----
// Background:       #eeece6 (warm beige)
// Surface:          #f5f4f0 (sidebar / list panel)
// Surface 2:        #ffffff (cards, chat area)
// Text primary:     #1a1a18
// Text secondary:   #5f5e5a
// Text muted:       #888780
// Border:           rgba(0,0,0,0.12)
// Border strong:    rgba(0,0,0,0.22)
// Accent (primary): #1D9E75 (teal green)
// Accent hover:     #159167
// Accent bg:        #E1F5EE (light green background)
// Accent text:      #0F6E56
// Accent text strong: #085041
// OK / success:     #1D9E75
// Warning:          #854F0B
// Warning bg:       #FAEEDA
// Error:            #A32D2D
// Error bg:         #FCEBEB
// Bubble outgoing:  #1D9E75 (teal, white text)
// Bubble incoming:  #ffffff (white, dark text, light border)

// ---- Dark palette (remapped from the webclient's original theme) ----
// Background:       #0f1115
// Surface:          #171a21
// Surface 2:        #1e2230
// Text primary:     #e6e8ee
// Text secondary:   #9aa1b2
// Text muted:       #6d7689
// Border:           #2a2f3c
// Border strong:    #3a4254
// Accent:           #5eb0ff (blue)
// Accent hover:     #7dc0ff
// Accent bg:        #1a3a5c
// Accent text:      #a8d0ff
// OK:               #4ade80
// Warning:          #fbbf24
// Error:            #f87171
// Bubble outgoing:  #1a3a5c
// Bubble incoming:  #1e2230

// ---- Typography ----
// UI font:   system-ui / -apple-system (sans-serif) → Material default sans-serif
// Mono font: Menlo / Consolas → use FontFamily.Monospace
// Body:      13-14sp
// Labels:    10-11sp
// Headings:  15-16sp, weight 500

// ---- Spacing ----
// Border radius small:  4dp
// Border radius medium: 8dp
// Border radius large:  12dp
// Standard padding:     16dp (cards), 14dp (list items), 18dp (headers)

// ---- Component patterns ----
//
// Avatar:
//   28-38dp circle, background from accent-bg, text from accent-text-strong
//   Initials: first 2 chars of display name, uppercased
//   If display name has 2+ words, use first letter of each word
//
// Message bubble:
//   Outgoing: accent background, white text, rounded corners with
//             bottom-right radius smaller (4dp vs 14dp)
//   Incoming: surface-2 background, primary text, thin border,
//             bottom-left radius smaller
//   Max width: 70% of container (desktop) / 80% (mobile)
//   Meta line: timestamp + state glyph (⏳ pending, ↑ sending,
//              ✓ sent, ✓✓ delivered, ✗ failed)
//
// Contact list row:
//   Avatar + name + last-message-preview + unread badge
//   Unread badge: accent bg, small pill with count
//   Active/selected: accent-bg background
//
// Node list row:
//   Display label (parsed telemetry or service name)
//   Meta: identity hash, dest hash, service name or name_hash,
//         RSSI, last-seen timestamp
//   Green dot indicator for nodes with coordinates
//   Click → pan map to marker
//
// Connection status pill:
//   Small pill with dot + text: "Connected (BLE)" or "Disconnected"
//   Connected: accent-bg background, accent-text color, lit dot
//   Disconnected: muted background, muted text, dim dot
//
// Status glyphs for outgoing messages:
//   ⏳ pending (muted) — queued, radio off
//   ↑  sending (accent) — TX in flight
//   ✓  sent (muted) — transmitted, awaiting receipt
//   ✓✓ delivered (accent) — delivery proof received
//   ✗  failed (error) — all retries exhausted

// @Composable
// fun ReticulumTheme(
//     darkTheme: Boolean = isSystemInDarkTheme(),
//     content: @Composable () -> Unit,
// ) {
//     val colorScheme = if (darkTheme) darkColorScheme(...) else lightColorScheme(...)
//     MaterialTheme(colorScheme = colorScheme, typography = ..., content = content)
// }
