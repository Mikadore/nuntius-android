package dev.mikadore.nuntius

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.gateway.GatewayService
import dev.mikadore.nuntius.gateway.GatewayLogger
import dev.mikadore.nuntius.ui.theme.NuntiusTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AuthenticatedApp : ComponentActivity() {
    @Inject
    lateinit var gatewayLogger: GatewayLogger

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        gatewayLogger.info(
            if (granted) {
                "Notification permission granted."
            } else {
                "Notification permission denied; foreground service visibility may be limited."
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayService.start(this)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            NuntiusTheme {
                AuthenticatedAppContent()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedAppContent(
    sidebarViewModel: SidebarViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val selectedRoute by sidebarViewModel.selectedRoute.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val currentDestination = AppDestination.fromRoute(currentRoute)

    LaunchedEffect(currentRoute) {
        sidebarViewModel.onRouteChanged(currentRoute)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Nuntius",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
                )
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                AppDestination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                        selected = selectedRoute == destination.route,
                        onClick = {
                            sidebarViewModel.onDestinationSelected(destination)
                            navController.navigateTo(destination)
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(currentDestination?.label ?: "Nuntius") },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open navigation drawer",
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            AuthenticatedNavHost(
                navController = navController,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AuthenticatedNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.HOME.route,
        modifier = modifier,
    ) {
        composable(AppDestination.HOME.route) {
            HomePage()
        }
        composable(AppDestination.FAVORITES.route) {
            DestinationPage(AppDestination.FAVORITES)
        }
        composable(AppDestination.PROFILE.route) {
            DestinationPage(AppDestination.PROFILE)
        }
    }
}

@Composable
private fun DestinationPage(destination: AppDestination) {
    Text(destination.label)
}

private fun NavHostController.navigateTo(destination: AppDestination) {
    if (currentDestination?.route == destination.route) return

    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

enum class AppDestination(
    val route: String,
    val label: String,
    val iconRes: Int,
) {
    HOME("home", "Home", R.drawable.ic_home),
    FAVORITES("favorites", "Favorites", R.drawable.ic_favorite),
    PROFILE("profile", "Profile", R.drawable.ic_account_box),
    ;

    companion object {
        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}
