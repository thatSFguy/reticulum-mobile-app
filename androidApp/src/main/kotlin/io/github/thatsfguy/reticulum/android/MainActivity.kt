package io.github.thatsfguy.reticulum.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.storage.Preferences
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.android.ui.screens.MessagesScreen
import io.github.thatsfguy.reticulum.android.ui.screens.NodesScreen
import io.github.thatsfguy.reticulum.android.ui.screens.NomadScreen
import io.github.thatsfguy.reticulum.android.ui.screens.RoomsScreen
import io.github.thatsfguy.reticulum.android.ui.screens.SettingsScreen
import io.github.thatsfguy.reticulum.android.ui.theme.ReticulumTheme
import io.github.thatsfguy.reticulum.transport.TransportState

class MainActivity : ComponentActivity() {

    private var boundService: ReticulumService? = null
    private val viewModel: ReticulumViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ReticulumViewModel::class.java]
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? ReticulumService.LocalBinder)?.service ?: return
            boundService = svc
            viewModel.bind(svc)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.unbind()
            boundService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result map ignored — UI will check via BlePermissions on next attempt */ }

    // File picker registered at the Activity level — the same mechanism as
    // permissionLauncher above, NOT a Compose rememberLauncherForActivityResult.
    // On a reporter's device the Compose-remembered launcher's result was
    // never delivered (the registry drops it when the host Activity is
    // recreated mid-pick), while Activity-level registration survives — it's
    // the moral equivalent of Sideband's classic startActivityForResult.
    // The picked Uri is routed to the ViewModel, which the conversation
    // composer collects.
    private val filePickLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.onFilePicked(uri)
    }

    /** Launch the system file picker (ACTION_GET_CONTENT). Called from the
     *  conversation composer's attach menu. */
    fun pickFile() {
        runCatching { filePickLauncher.launch("*/*") }
            .onFailure {
                android.widget.Toast.makeText(
                    this, "No app available to pick a file", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }

    // Save (CreateDocument) registered at the Activity level for the same
    // reason as filePickLauncher: the per-composition launcher dropped its
    // result on a reporter's device (Activity recreation during the SAF
    // pick), so the bytes were never written and SAF's pre-created file
    // stayed at 0 B — the "downloads as 0 B" bug. The bytes to write are
    // staged in the ViewModel (which survives recreation) by saveFile().
    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val bytes = viewModel.takeStagedSave()
        if (uri == null) return@registerForActivityResult  // cancelled
        if (bytes == null) {
            // Nothing staged (shouldn't happen) — remove the empty doc SAF
            // created so it can't masquerade as a saved file.
            runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("openOutputStream null")
            }
            android.util.Log.i(
                "ReticulumSave",
                "save -> " + if (res.isSuccess) "wrote ${bytes.size} B"
                else "failed: ${res.exceptionOrNull()?.message}",
            )
            if (res.isFailure) {
                runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (res.isSuccess) "Saved ${bytes.size} B"
                    else "Couldn't save: ${res.exceptionOrNull()?.message}",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Save [bytes] to a user-chosen location via SAF, suggesting
     *  [suggestedName]. The bytes are staged in the ViewModel so they
     *  survive an Activity recreation during the picker. */
    fun saveFile(suggestedName: String, bytes: ByteArray) {
        viewModel.stageSave(bytes)
        runCatching { createDocLauncher.launch(suggestedName) }
            .onFailure {
                viewModel.takeStagedSave()
                android.widget.Toast.makeText(
                    this, "No app available to save the file", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Preferences(applicationContext)
        // First-ever launch lands on Settings (the Connect section)
        // instead of an empty Messages list — there is nothing to do
        // until a transport is attached. One-shot; consumed here.
        val firstLaunch = prefs.isFirstLaunch
        if (firstLaunch) prefs.markFirstLaunchDone()
        setContent {
            // Theme follows the user's preference; "system" defers to
            // the OS dark/light setting. Reacts live to a change made
            // in Settings → Appearance.
            val themePref by viewModel.themePreference.collectAsState(
                initial = prefs.themePreference.value,
            )
            val darkTheme = when (themePref) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ReticulumTheme(darkTheme = darkTheme) {
                ReticulumApp(viewModel, startOnSettings = firstLaunch) { perms ->
                    permissionLauncher.launch(perms)
                }
            }
        }
        // Up front, request ONLY POST_NOTIFICATIONS — incoming-message
        // notifications matter on every transport (incl. TCP-only). BLE
        // scan/connect permissions are deliberately NOT requested here
        // (issue #34): a TCP-only user shouldn't be prompted to "search
        // for local devices" at first launch. Those are requested on
        // demand when the user taps RNode (Bluetooth) → Add node
        // (see SettingsScreen's onRequestPermissions path).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
        }
        // Notification-tap deep link on cold-start: the launcher Intent
        // carries EXTRA_OPEN_CONTACT when the user opened the app from
        // an incoming-message notification.
        handleDeepLink(intent)
        // Cold-start auto-reconnect: re-establish the transport the app
        // was connected to when last shut down. Gated on a saved record
        // existing (and auto-reconnect being on) so a launch with
        // nothing to restore doesn't spin up a foreground service just
        // to stop it. The service itself no-ops on a warm start.
        if (prefs.resolveConnectionMemories().isNotEmpty()) {
            ReticulumService.restoreLastConnection(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Manifest declares launchMode="singleTask", so subsequent
        // notification taps on an already-running activity arrive here
        // instead of onCreate. Update the stored intent so any later
        // getIntent() reads the freshest one, and forward to the deep-
        // link handler.
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val contactHash = intent?.getStringExtra(ReticulumService.EXTRA_OPEN_CONTACT)
        if (!contactHash.isNullOrEmpty()) {
            viewModel.openContact(contactHash)
            // Clear the extra so we don't re-trigger on configuration
            // changes (which give us back the same Intent on
            // savedInstanceState restoration).
            intent.removeExtra(ReticulumService.EXTRA_OPEN_CONTACT)
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to whatever instance of the service is alive. We don't start it
        // here — Settings.connect() does that explicitly when the user clicks
        // Connect, so binding without a started service is a no-op until then.
        bindService(
            Intent(this, ReticulumService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(serviceConnection) }
        viewModel.unbind()
    }
}

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Messages : Tab("messages", "Messages", Icons.Default.Email)
    data object Nodes    : Tab("nodes", "Nodes", Icons.Default.Place)
    data object Nomad    : Tab("nomad", "Nomad", Icons.Default.Info)
    data object Rooms    : Tab("rooms", "Rooms", Icons.AutoMirrored.Filled.List)
    data object Settings : Tab("settings", "Settings", Icons.Default.Settings)
}

// Graph is no longer a top-level tab — it folded into the Nodes tab as a
// Nodes/Graph pane switch (NodesScreen) to free a bottom-nav slot for the
// RRC Rooms feature. Five is the Material 3 NavigationBar maximum, so the
// experimental Rooms tab only claims its slot when the user has enabled
// `experimentalRrc` — see the reactive `tabs` list in ReticulumApp.

@Composable
private fun ReticulumApp(
    viewModel: ReticulumViewModel,
    startOnSettings: Boolean,
    onRequestPermissions: (Array<String>) -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val connections by viewModel.connectionStates.collectAsState(initial = emptyList())
    val anyConnected = connections.any { it.transport == TransportState.Connected }

    // The experimental RRC Rooms tab only appears when the user has
    // opted in via Settings. Recomputed when the preference flips.
    val rrcEnabled by viewModel.experimentalRrc.collectAsState(initial = false)
    val nomadEnabled by viewModel.nomadEnabled.collectAsState(initial = false)
    val tabs = remember(rrcEnabled, nomadEnabled) {
        buildList {
            add(Tab.Nodes); add(Tab.Messages)
            if (nomadEnabled) add(Tab.Nomad)
            if (rrcEnabled) add(Tab.Rooms)
            add(Tab.Settings)
        }
    }

    // Notification deep-link consumer: when the Activity pushes a
    // contact hash onto pendingOpenContact (because the user tapped an
    // incoming-message notification), select the conversation and
    // navigate to the Messages tab in one action.
    LaunchedEffect(Unit) {
        viewModel.pendingOpenContact.collect { hash ->
            viewModel.selectDestination(hash)
            nav.navigate(Tab.Messages.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Rooms deep-link: the user promoted a discovered rrc.hub from the
    // Nodes tab — jump to Rooms so the new hub is visible.
    LaunchedEffect(Unit) {
        viewModel.pendingShowRooms.collect {
            nav.navigate(Tab.Rooms.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Nomad deep-link: the user tapped a `<destHash>:/path` cross-
    // node link inside an LXMF message — switch to the Nomad tab so
    // the NomadScreen observer of pendingNomadSelection can pick up
    // the queued (hash, path) and load the page. The actual
    // selection state is consumed in NomadScreen on next
    // composition; here we just route the user to the right tab.
    LaunchedEffect(Unit) {
        viewModel.pendingShowNomadPage.collect {
            nav.navigate(Tab.Nomad.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                tabs.forEach { tab ->
                    // Settings icon goes red when no transport is up — this
                    // is the entire connectivity indicator now (no top bar).
                    // The error color follows the Material 3 theme so dark/
                    // light themes both pick a sensible red.
                    val tintSettingsRed = tab == Tab.Settings && !anyConnected
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint = if (tintSettingsRed)
                                    MaterialTheme.colorScheme.error
                                else
                                    LocalContentColor.current,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                nav,
                startDestination =
                    if (startOnSettings) Tab.Settings.route else Tab.Messages.route,
            ) {
                composable(Tab.Messages.route) { MessagesScreen(viewModel) }
                composable(Tab.Nodes.route)    { NodesScreen(viewModel) }
                composable(Tab.Nomad.route)    { NomadScreen(viewModel) }
                composable(Tab.Rooms.route)    { RoomsScreen(viewModel) }
                composable(Tab.Settings.route) { SettingsScreen(viewModel, onRequestPermissions) }
            }
        }
    }
}

