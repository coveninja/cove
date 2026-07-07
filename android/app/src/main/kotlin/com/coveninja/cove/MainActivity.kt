package com.coveninja.cove

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.api.Media
import com.coveninja.cove.api.ServerMode
import com.coveninja.cove.api.ServerModeStore
import com.coveninja.cove.auth.AuthViewModel
import com.coveninja.cove.sync.SyncCoordinator
import com.coveninja.cove.ui.*
import com.coveninja.cove.ui.settings.AddonsScreen
import com.coveninja.cove.ui.settings.SettingsScreen
import com.coveninja.cove.ui.theme.CoveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            CoveTheme {
                CoveApp()
            }
        }
    }
}

enum class Tab { HOME, MY_LIST, EXPLORE, SEARCH, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoveApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var detailMedia by remember { mutableStateOf<Media?>(null) }
    var streamsMedia by remember { mutableStateOf<Media?>(null) }
    var streamsSeason by remember { mutableStateOf<Int?>(null) }
    var streamsEpisode by remember { mutableStateOf<Int?>(null) }
    var backendReady by remember { mutableStateOf(false) }
    var showAddons by remember { mutableStateOf(false) }

    // AuthViewModel is activity-scoped so it survives tab switches and the
    // probe fires exactly once when the backend comes up.
    val authVm: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    // Poll /api/ping until the backend is reachable. In Remote mode the local embedded
    // backend still starts, but the UI talks to the remote host, so we also accept the
    // remote backend as "ready" to avoid a stale wait.
    LaunchedEffect(Unit) {
        val isRemote = ServerModeStore.get() is ServerMode.Remote
        if (isRemote) {
            // Remote mode: the app talks to a backend that's already running on the host.
            // Skip waiting for the local embedded backend — content requests will go to
            // the configured remote URL directly.
            backendReady = true
            return@LaunchedEffect
        }
        val client = OkHttpClient()
        // Strip /api suffix then re-add /api/ping so we handle any base path.
        val pingUrl = BuildConfig.BACKEND_URL.replace("/api", "") + "/api/ping"
        while (!backendReady) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(pingUrl).build()).execute()
                }
                if (resp.isSuccessful) {
                    resp.close()
                    backendReady = true
                } else {
                    resp.close()
                }
            } catch (_: Exception) {}
            if (!backendReady) delay(1000)
        }
    }

    // When the backend becomes ready, fire the auth probe. This is the
    // "startup background sync" step: POST /api/auth/sync with the stored JWT
    // (already loaded into CoveApiClient by Application.onCreate). A 200
    // means we're signed in and sync ran; 401 clears the stored session; 503
    // signals auth unavailable (OSS build or missing config).
    LaunchedEffect(backendReady) {
        if (backendReady) {
            authVm.probeOnBackendReady()
        }
    }

    // When SyncCoordinator's background auto-sync returns 401 (token expired),
    // delegate to AuthViewModel to clear state and return the user to sign-in.
    LaunchedEffect(Unit) {
        SyncCoordinator.signedOutEvents.collect {
            authVm.handleSyncSignOut()
        }
    }

    // AddonsScreen: full-screen overlay, shown from within SettingsScreen.
    if (showAddons) {
        AddonsScreen(onBack = { showAddons = false })
        return
    }

    Scaffold { innerPadding ->
        // The home hero is a full-bleed backdrop, so let it draw behind the
        // status bar. Every other tab keeps the top inset so its content clears
        // the bar; the bottom/side insets are always honoured.
        val layoutDir = LocalLayoutDirection.current
        val contentPadding = if (tab == Tab.HOME) {
            PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDir),
                end = innerPadding.calculateEndPadding(layoutDir),
                bottom = innerPadding.calculateBottomPadding(),
            )
        } else {
            innerPadding
        }
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            if (!backendReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Starting backend…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                val openDetail: (Media) -> Unit = { detailMedia = it }
                // Crossfade between tabs for a smooth transition. Keyed on `tab`, so
                // only the screen content animates — the nav pill below stays put.
                Crossfade(
                    targetState = tab,
                    animationSpec = tween(durationMillis = 250),
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.HOME -> HomeScreen(
                            onOpenDetail = openDetail,
                            onStreamsRequested = { m, s, e ->
                                detailMedia = null
                                streamsMedia = m
                                streamsSeason = s
                                streamsEpisode = e
                            },
                        )
                        Tab.MY_LIST -> LibraryScreen(onOpenDetail = openDetail)
                        Tab.EXPLORE -> ExploreScreen(onOpenDetail = openDetail)
                        Tab.SEARCH -> SearchScreen(onOpenDetail = openDetail)
                        Tab.SETTINGS -> SettingsScreen(authVm = authVm, onOpenAddons = { showAddons = true })
                    }
                }
            }

            // Floating pill nav bar overlaid on the content. Rendered here (not in
            // Scaffold's bottomBar) so no opaque band is reserved around it — the
            // page shows through behind the rounded pill instead of a dark frame.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                color = NavigationBarDefaults.containerColor,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoveNavItem(
                        selected = tab == Tab.HOME,
                        selectedIcon = Icons.Filled.Home,
                        unselectedIcon = Icons.Outlined.Home,
                        contentDescription = "Home",
                        onClick = { tab = Tab.HOME },
                    )
                    CoveNavItem(
                        selected = tab == Tab.MY_LIST,
                        selectedIcon = Icons.Filled.Bookmark,
                        unselectedIcon = Icons.Outlined.Bookmark,
                        contentDescription = "My List",
                        onClick = { tab = Tab.MY_LIST },
                    )
                    CoveNavItem(
                        selected = tab == Tab.EXPLORE,
                        selectedIcon = Icons.Filled.LocalFireDepartment,
                        unselectedIcon = Icons.Outlined.LocalFireDepartment,
                        contentDescription = "Explore",
                        onClick = { tab = Tab.EXPLORE },
                    )
                    CoveNavItem(
                        selected = tab == Tab.SEARCH,
                        selectedIcon = Icons.Filled.Search,
                        unselectedIcon = Icons.Outlined.Search,
                        contentDescription = "Search",
                        onClick = { tab = Tab.SEARCH },
                    )
                    CoveNavItem(
                        selected = tab == Tab.SETTINGS,
                        selectedIcon = Icons.Filled.Settings,
                        unselectedIcon = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        onClick = { tab = Tab.SETTINGS },
                    )
                }
            }
        }
    }

    // Detail sheet floats over the current tab, Netflix-style.
    detailMedia?.let { media ->
        MediaDetailSheet(
            media = media,
            onDismiss = { detailMedia = null },
            onStreamsRequested = { m, s, e ->
                detailMedia = null
                streamsMedia = m
                streamsSeason = s
                streamsEpisode = e
            },
        )
    }

    // Stream selection sheet.
    streamsMedia?.let { media ->
        StreamsSheet(
            media = media,
            season = streamsSeason,
            episode = streamsEpisode,
            onDismiss = { streamsMedia = null },
        )
    }
}

@Composable
private fun CoveNavItem(
    selected: Boolean,
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = contentDescription,
            tint = color,
        )
    }
}
