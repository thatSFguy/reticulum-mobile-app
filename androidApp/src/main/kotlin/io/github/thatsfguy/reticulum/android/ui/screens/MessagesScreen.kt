package io.github.thatsfguy.reticulum.android.ui.screens

/**
 * Messages screen — the primary view, matching the webclient's
 * Messages tab.
 *
 * Layout on phones (< 600dp):
 *   Default: full-width contact list, scrollable.
 *   On contact tap: navigate to conversation view (full-screen),
 *     with a back arrow in the top bar to return to the list.
 *
 * Layout on tablets (≥ 600dp):
 *   Two-pane: contact list on the left (260dp), conversation on the
 *   right (flex fill). Optional right panel (240dp) showing identity
 *   + connection status + Announce button (same as the webclient's
 *   right panel in desktop mode).
 *
 * ---- Contact list ----
 * Each row:
 *   [Avatar 34dp] [Name + unread badge]  [timestamp]
 *                  [last message preview]  [online dot]
 *
 * Avatar: 34dp circle, accent-bg, initials (2 chars from displayName).
 * Unread badge: small pill (accent bg, contrast text) with count,
 *   shown next to the name when unreadCount > 0.
 * Active row: accent-bg background.
 * Swipe-to-delete: left swipe reveals a delete action. Confirm with
 *   a dialog ("Delete contact and all messages?").
 *
 * ---- Conversation view ----
 * Top bar: back arrow (phone only), contact avatar + name + hash.
 * Message list: scrollable, newest at bottom, auto-scroll on new.
 *   Outgoing bubbles: right-aligned, accent bg, white text.
 *     Bottom-right corner radius smaller (4dp vs 14dp).
 *     Meta: timestamp + state glyph (⏳↑✓✓✓✗).
 *   Incoming bubbles: left-aligned, surface-2 bg, primary text,
 *     thin border. Bottom-left corner radius smaller.
 *     Meta: timestamp.
 * Compose bar at bottom: text field (rounded, surface bg) + send
 *   button (accent circle with arrow icon). Enter key sends on
 *   hardware keyboard; soft keyboard shows a send action.
 *
 * ---- Empty state ----
 * When no contacts: centered text "Listening for announces…"
 * When not connected: show connect prompt (similar to the webclient's
 *   quick-connect hero on mobile — BLE and Serial buttons).
 *
 * TODO: Implement with Jetpack Compose.
 *   Key Compose components:
 *     LazyColumn for contact list
 *     LazyColumn for message list (reversed layout, auto-scroll)
 *     TextField + IconButton for compose bar
 *     Material 3 NavigationBar for bottom tabs
 *     Scaffold + TopAppBar for screen chrome
 *     adaptive layout via WindowSizeClass for phone vs tablet
 */

// @Composable fun MessagesScreen(...) { }
// @Composable fun ContactList(...) { }
// @Composable fun ContactRow(...) { }
// @Composable fun ConversationView(...) { }
// @Composable fun MessageBubble(...) { }
// @Composable fun ComposeBar(...) { }
