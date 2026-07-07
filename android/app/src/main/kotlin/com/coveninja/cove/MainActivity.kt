package com.coveninja.cove

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (tab == Tab.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                            contentDescription = "Home",
                        )
                    },
                    label = { Text("Home") },
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (tab == Tab.MY_LIST) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                            contentDescription = "My List",
                        )
                    },
                    label = { Text("My List") },
                    selected = tab == Tab.MY_LIST,
                    onClick = { tab = Tab.MY_LIST },
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (tab == Tab.EXPLORE) Icons.Filled.LocalFireDepartment
                            else Icons.Outlined.LocalFireDepartment,
                            contentDescription = "Explore",
                        )
                    },
                    label = { Text("Explore") },
                    selected = tab == Tab.EXPLORE,
                    onClick = { tab = Tab.EXPLORE },
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (tab == Tab.SEARCH) Icons.Filled.Search else Icons.Outlined.Search,
                            contentDescription = "Search",
                        )
                    },
                    label = { Text("Search") },
                    selected = tab == Tab.SEARCH,
                    onClick = { tab = Tab.SEARCH },
                )
                NavigationBarItem(
                    icon = {
                        Icon(
                            if (tab == Tab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings",
                        )
                    },
                    label = { Text("Settings") },
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
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
                if (tab == Tab.HOME) HomeScreen(
                    onOpenDetail = openDetail,
                    onStreamsRequested = { m, s, e ->
                        detailMedia = null
                        streamsMedia = m
                        streamsSeason = s
                        streamsEpisode = e
                    },
                )
                if (tab == Tab.MY_LIST) LibraryScreen(onOpenDetail = openDetail)
                if (tab == Tab.EXPLORE) ExploreScreen(onOpenDetail = openDetail)
                if (tab == Tab.SEARCH) SearchScreen(onOpenDetail = openDetail)
                if (tab == Tab.SETTINGS) SettingsScreen(authVm = authVm, onOpenAddons = { showAddons = true })
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
