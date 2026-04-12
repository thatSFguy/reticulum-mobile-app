package io.github.thatsfguy.reticulum.android

/**
 * Main activity — entry point for the Android app.
 *
 * Uses Jetpack Compose with Material 3 bottom navigation:
 *   - Messages (default tab)
 *   - Nodes
 *   - Settings
 *
 * Navigation: NavHost with three destinations matching the tabs.
 * On phones, the Messages tab uses a nested navigation where
 * tapping a contact pushes a ConversationScreen and a back press
 * returns to the list.
 *
 * Service binding: when the user clicks Connect, the Activity
 * starts ReticulumService as a foreground service and binds to it.
 * The service owns the BLE/WebSocket connection. The Activity
 * observes the service's packet flow via a SharedFlow or LiveData
 * exposed by the service binder. When the Activity is destroyed
 * (config change, back press), it unbinds but the service keeps
 * running. When the Activity recreates, it re-binds and drains
 * any buffered packets.
 *
 * ViewModel: a single ReticulumViewModel (or per-screen ViewModels)
 * holds the UI state: contacts list, active conversation messages,
 * nodes list, connection status, radio status, log lines. The
 * ViewModel talks to the service binder and the Room repositories.
 *
 * Permission flow:
 *   1. On first launch, check and request POST_NOTIFICATIONS (Android 13+).
 *   2. On Connect (BLE): request BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
 *   3. On Connect (Serial): request USB host permission via Intent.
 *   4. On Connect (WebSocket): no runtime permissions needed.
 *
 * Theme: ReticulumTheme (see ui/theme/Theme.kt) wraps the content.
 * Light/dark follows the user's Appearance setting (persisted in
 * SharedPreferences), defaulting to system.
 *
 * TODO: Implement.
 */

// class MainActivity : ComponentActivity() {
//     override fun onCreate(savedInstanceState: Bundle?) {
//         super.onCreate(savedInstanceState)
//         setContent {
//             ReticulumTheme {
//                 ReticulumApp()
//             }
//         }
//     }
// }
//
// @Composable
// fun ReticulumApp() {
//     val navController = rememberNavController()
//     Scaffold(
//         bottomBar = {
//             NavigationBar {
//                 NavigationBarItem(icon = ..., label = "Messages", ...)
//                 NavigationBarItem(icon = ..., label = "Nodes", ...)
//                 NavigationBarItem(icon = ..., label = "Settings", ...)
//             }
//         }
//     ) { padding ->
//         NavHost(navController, startDestination = "messages") {
//             composable("messages") { MessagesScreen(...) }
//             composable("nodes") { NodesScreen(...) }
//             composable("settings") { SettingsScreen(...) }
//         }
//     }
// }
