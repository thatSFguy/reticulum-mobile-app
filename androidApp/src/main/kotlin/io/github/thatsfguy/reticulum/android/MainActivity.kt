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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thatsfguy.reticulum.android.platform.BlePermissions
import io.github.thatsfguy.reticulum.android.service.ReticulumService
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.android.ui.screens.GraphScreen
import io.github.thatsfguy.reticulum.android.ui.screens.MessagesScreen
import io.github.thatsfguy.reticulum.android.ui.screens.NodesScreen
import io.github.thatsfguy.reticulum.android.ui.screens.NomadScreen
import io.github.thatsfguy.reticulum.android.ui.screens.SettingsScreen
import io.github.thatsfguy.reticulum.android.ui.theme.ReticulumTheme
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReticulumTheme {
                ReticulumApp(viewModel) { perms -> permissionLauncher.launch(perms) }
            }
        }
        // Best-effort: ask for missing permissions up front.
        val missing = BlePermissions.missing(this)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
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
    data object Graph    : Tab("graph", "Graph", Icons.Default.Share)
    data object Settings : Tab("settings", "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Messages, Tab.Nodes, Tab.Nomad, Tab.Graph, Tab.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReticulumApp(
    viewModel: ReticulumViewModel,
    onRequestPermissions: (Array<String>) -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val connections by viewModel.connectionStates.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reticulum") },
                actions = {
                    ConnectionDot(
                        states = connections,
                        onClick = {
                            nav.navigate(Tab.Settings.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Tab.Messages.route) {
                composable(Tab.Messages.route) { MessagesScreen(viewModel) }
                composable(Tab.Nodes.route)    { NodesScreen(viewModel) }
                composable(Tab.Nomad.route)    { NomadScreen(viewModel) }
                composable(Tab.Graph.route)    { GraphScreen(viewModel) }
                composable(Tab.Settings.route) { SettingsScreen(viewModel, onRequestPermissions) }
            }
        }
    }
}

/**
 * Tiny round indicator in the top-right of the global app bar.
 * Green when at least one transport is Connected, amber when any is
 * mid-Connecting, muted gray otherwise. Tapping it navigates to the
 * Settings tab where the user can connect or check status detail.
 */
@Composable
private fun ConnectionDot(
    states: List<ReticulumEngine.ConnectionState>,
    onClick: () -> Unit,
) {
    val anyConnected  = states.any { it.transport == TransportState.Connected }
    val anyConnecting = states.any { it.transport == TransportState.Connecting }
    val color = when {
        anyConnected  -> Color(0xFF1D9E75) // accent green — same shade as light-theme accent
        anyConnecting -> Color(0xFFE8A23B) // amber
        else          -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    val description = when {
        anyConnected  -> "Connected — tap for details"
        anyConnecting -> "Connecting — tap for details"
        else          -> "Disconnected — tap to connect"
    }
    // Outer Box gives the dot a 48dp tap target without making the
    // visible mark itself any bigger than 12dp.
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
