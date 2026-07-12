package com.coveninja.cove.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coveninja.cove.BuildConfig
import com.coveninja.cove.api.AddonEntry
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.ServerMode
import com.coveninja.cove.api.ServerModeStore
import com.coveninja.cove.api.Settings
import com.coveninja.cove.auth.AuthForm
import com.coveninja.cove.auth.AuthState
import com.coveninja.cove.auth.AuthViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsViewModel : ViewModel() {
    // Current persisted server mode (shown in the UI)
    var serverMode by mutableStateOf<ServerMode>(ServerModeStore.get())
        private set

    // Edit-in-progress fields for remote mode configuration
    var remoteHost by mutableStateOf(
        (ServerModeStore.get() as? ServerMode.Remote)?.baseUrl ?: ""
    )
    var remoteToken by mutableStateOf(
        (ServerModeStore.get() as? ServerMode.Remote)?.token ?: ""
    )
    var showToken by mutableStateOf(false)

    // Connection test feedback
    var testResult by mutableStateOf<Boolean?>(null)   // null = not tested, true = ok, false = fail
    var testInProgress by mutableStateOf(false)

    // Loaded settings from the backend
    var settings by mutableStateOf<Settings?>(null)
        private set
    var settingsSaving by mutableStateOf(false)
    var settingsSaveError by mutableStateOf<String?>(null)

    // Enabled provider addons for the defaultProvider dropdown.
    var providerAddons by mutableStateOf<List<AddonEntry>>(emptyList())
        private set

    // Whether the AddonsScreen is open
    var showAddons by mutableStateOf(false)

    init { loadSettings() }

    fun loadSettings() {
        viewModelScope.launch {
            try {
                settings = CoveApiClient.get<Settings>("/settings")
            } catch (_: Exception) {
                // Non-fatal — playback still works without settings loaded
            }
            try {
                val all: List<AddonEntry> = CoveApiClient.get("/addons")
                providerAddons = all.filter { it.kind == "provider" && it.enabled }
            } catch (_: Exception) {
                // Non-fatal
            }
        }
    }

    fun testRemoteConnection() {
        testResult = null
        testInProgress = true
        viewModelScope.launch {
            testResult = CoveApiClient.testConnection(remoteHost, remoteToken)
            testInProgress = false
        }
    }

    fun saveRemoteMode() {
        // Only persist if the last test succeeded
        ServerModeStore.setRemote(remoteHost, remoteToken)
        serverMode = ServerMode.Remote(remoteHost, remoteToken)
        loadSettings()
    }

    fun switchToLocal() {
        ServerModeStore.setLocal()
        serverMode = ServerMode.Local
        remoteHost = ""
        remoteToken = ""
        testResult = null
        loadSettings()
    }

    fun saveSettings(updated: Settings) {
        settingsSaving = true
        settingsSaveError = null
        viewModelScope.launch {
            try {
                val body = Json { encodeDefaults = true }.encodeToString(updated)
                val saved = CoveApiClient.put<Settings>("/settings", body)
                if (saved != null) settings = saved
                else settingsSaveError = "Save failed"
            } catch (e: Exception) {
                settingsSaveError = e.message
            } finally {
                settingsSaving = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    authVm: AuthViewModel = viewModel(),
    onOpenAddons: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp))

        // ── Account / Supabase sync ────────────────────────────────────────
        AccountSection(authVm)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ── Server section ─────────────────────────────────────────────────
        SectionTitle("Server")

        val isRemote = vm.serverMode is ServerMode.Remote
        // showRemoteFields: true when already in Remote mode OR user toggled the switch toward Remote
        var showRemoteFields by remember { mutableStateOf(isRemote) }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Local (embedded)", modifier = Modifier.weight(1f))
            Switch(
                checked = showRemoteFields,
                onCheckedChange = { toRemote ->
                    showRemoteFields = toRemote
                    if (!toRemote) vm.switchToLocal()
                    // Switching to remote: user fills in fields below and taps "Test + Save"
                },
            )
            Text("Remote", modifier = Modifier.padding(start = 8.dp))
        }

        if (showRemoteFields) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.remoteHost,
                onValueChange = { vm.remoteHost = it; vm.testResult = null },
                label = { Text("Host URL (e.g. http://192.168.1.5:6969/api)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = vm.remoteToken,
                onValueChange = { vm.remoteToken = it; vm.testResult = null },
                label = { Text("Pairing token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (vm.showToken) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { vm.showToken = !vm.showToken }) {
                        Text(if (vm.showToken) "Hide" else "Show")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.testRemoteConnection() },
                    enabled = vm.remoteHost.isNotBlank() && vm.remoteToken.isNotBlank() && !vm.testInProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    if (vm.testInProgress) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Test connection")
                }
                Button(
                    onClick = { vm.saveRemoteMode() },
                    enabled = vm.testResult == true,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            vm.testResult?.let { ok ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (ok) "Connected successfully" else "Connection failed — check host and token",
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ── Remote Access ──────────────────────────────────────────────────
        // Placed near the Server section since it controls LAN connectivity.
        val s = vm.settings
        if (s != null) {
            // Keep a single top-level draft for the whole settings block.
            // Remember key = s so the draft resets to the latest server state after each save.
            var draft by remember(s) { mutableStateOf(s) }

            SectionTitle("Remote Access")

            SettingsToggle("Enable remote access", draft.remoteAccessEnabled) {
                draft = draft.copy(remoteAccessEnabled = it)
                vm.saveSettings(draft)
                // After toggling on, saveSettings updates vm.settings with the server
                // response (which includes the generated token). The remember(s) key
                // changes and draft is reset to the saved value — picking up the token
                // without any extra logic.
            }
            Text(
                "Allow other devices on your network to connect to this device's Cove server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            if (draft.remoteAccessEnabled && draft.remoteAccessToken.isNotBlank()) {
                val clipboardManager = LocalClipboardManager.current
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        draft.remoteAccessToken,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(draft.remoteAccessToken))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy token",
                            modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    "Use this token on another device to pair.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Addons shortcut ────────────────────────────────────────────
            SectionTitle("Addons")
            OutlinedButton(
                onClick = onOpenAddons,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Manage addons")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Streaming ──────────────────────────────────────────────────
            SectionTitle("Streaming")

            SettingsToggle("Auto-select best stream", draft.autoSelectStream) {
                draft = draft.copy(autoSelectStream = it)
                vm.saveSettings(draft)
            }

            SettingsDropdown(
                label = "Stream selection strategy",
                value = draft.streamSelectionMode,
                options = listOf(
                    "balanced"  to "Balanced",
                    "seeders"   to "Most Seeders",
                    "quality"   to "Highest Quality",
                    "smallest"  to "Smallest File",
                    "bandwidth" to "Match Internet Speed",
                ),
                onSelect = {
                    draft = draft.copy(streamSelectionMode = it)
                    vm.saveSettings(draft)
                },
            )

            SettingsDropdown(
                label = "Source preference",
                value = draft.sourcePreference,
                options = listOf(
                    ""        to "No preference",
                    "torrent" to "Prefer torrents",
                    "direct"  to "Prefer direct streams",
                ),
                onSelect = {
                    draft = draft.copy(sourcePreference = it)
                    vm.saveSettings(draft)
                },
            )

            val providerOptions = listOf("" to "None") +
                vm.providerAddons.map { it.manifest.name to it.manifest.name }
            SettingsDropdown(
                label = "Preferred provider",
                value = draft.defaultProvider,
                options = providerOptions,
                onSelect = {
                    draft = draft.copy(defaultProvider = it)
                    vm.saveSettings(draft)
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Playback ───────────────────────────────────────────────────
            SectionTitle("Playback")

            SettingsToggle("Auto-play next episode", draft.autoPlay) {
                draft = draft.copy(autoPlay = it)
                vm.saveSettings(draft)
            }
            SettingsToggle("Remember position (resume)", draft.rememberPosition) {
                draft = draft.copy(rememberPosition = it)
                vm.saveSettings(draft)
            }
            SettingsToggle("Hide spoilers", draft.hideSpoilers) {
                draft = draft.copy(hideSpoilers = it)
                vm.saveSettings(draft)
            }

            SettingsDropdown(
                label = "Default audio language",
                value = draft.defaultAudioLang,
                options = listOf(
                    "original" to "Original language",
                    "en"       to "English",
                    "es"       to "Spanish",
                    "fr"       to "French",
                    "de"       to "German",
                    "pt"       to "Portuguese",
                    "it"       to "Italian",
                    "ja"       to "Japanese",
                    "ko"       to "Korean",
                    "zh"       to "Chinese",
                    "ar"       to "Arabic",
                    "ru"       to "Russian",
                    "hi"       to "Hindi",
                    "tr"       to "Turkish",
                ),
                onSelect = {
                    draft = draft.copy(defaultAudioLang = it)
                    vm.saveSettings(draft)
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Subtitles")

            SettingsToggle("Subtitles enabled", draft.subtitlesEnabled) {
                draft = draft.copy(subtitlesEnabled = it)
                vm.saveSettings(draft)
            }

            if (draft.subtitlesEnabled) {
                OutlinedTextField(
                    value = draft.defaultSubtitleLang,
                    onValueChange = { draft = draft.copy(defaultSubtitleLang = it) },
                    label = { Text("Subtitle language (ISO 639-1, e.g. en)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Text("Subtitle size: ${draft.subtitleSize.toInt()}%",
                    style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = draft.subtitleSize.toFloat(),
                    onValueChange = { draft = draft.copy(subtitleSize = it.toDouble()) },
                    valueRange = 50f..200f,
                    steps = 29,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Subtitle position: ${draft.subtitlePosition.toInt()}% from bottom",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = draft.subtitlePosition.toFloat(),
                    onValueChange = { draft = draft.copy(subtitlePosition = it.toDouble()) },
                    valueRange = 2f..90f,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsToggle("Subtitle background", draft.subtitleBackground) {
                    draft = draft.copy(subtitleBackground = it)
                    vm.saveSettings(draft)
                }
                Button(
                    onClick = { vm.saveSettings(draft) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.settingsSaving,
                ) {
                    if (vm.settingsSaving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Save subtitle settings")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Segment skip")

            SettingsToggle("Skip intro", draft.autoSkipIntro) {
                draft = draft.copy(autoSkipIntro = it)
                vm.saveSettings(draft)
            }
            SettingsToggle("Skip recap", draft.autoSkipRecap) {
                draft = draft.copy(autoSkipRecap = it)
                vm.saveSettings(draft)
            }
            SettingsToggle("Skip credits", draft.autoSkipCredits) {
                draft = draft.copy(autoSkipCredits = it)
                vm.saveSettings(draft)
            }
            SettingsToggle("Skip preview", draft.autoSkipPreview) {
                draft = draft.copy(autoSkipPreview = it)
                vm.saveSettings(draft)
            }

            vm.settingsSaveError?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── About ──────────────────────────────────────────────────────
            SectionTitle("About")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text("Version", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Text(BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

        } else {
            // Settings still loading — show addons button and spinner only.
            SectionTitle("Addons")
            OutlinedButton(
                onClick = onOpenAddons,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Manage addons")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── Account section ───────────────────────────────────────────────────────────

@Composable
private fun AccountSection(vm: AuthViewModel) {
    SectionTitle("Account")

    when (val state = vm.authState) {

        is AuthState.Loading -> {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Checking account…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        is AuthState.Unavailable -> {
            Text(
                "Account sync is not available in this build. " +
                "Build with SUPABASE_URL configured to enable cross-device sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        is AuthState.SignedOut -> {
            when (vm.activeForm) {
                AuthForm.LOGIN    -> LoginForm(vm)
                AuthForm.REGISTER -> RegisterForm(vm)
                AuthForm.CONFIRM_REGISTER -> ConfirmRegisterForm(vm)
            }
        }

        is AuthState.SignedIn -> {
            SignedInSection(vm, state)
        }
    }
}

@Composable
private fun LoginForm(vm: AuthViewModel) {
    OutlinedTextField(
        value = vm.email,
        onValueChange = { vm.email = it; vm.clearError() },
        label = { Text("Email") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = vm.password,
        onValueChange = { vm.password = it; vm.clearError() },
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Spacer(Modifier.height(8.dp))

    vm.errorMessage?.let { err ->
        Text(err, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.login() },
            enabled = !vm.isLoading,
            modifier = Modifier.weight(1f),
        ) {
            if (vm.isLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Sign in")
        }
        OutlinedButton(
            onClick = { vm.showRegister() },
            modifier = Modifier.weight(1f),
        ) { Text("Register") }
    }
}

@Composable
private fun RegisterForm(vm: AuthViewModel) {
    OutlinedTextField(
        value = vm.email,
        onValueChange = { vm.email = it; vm.clearError() },
        label = { Text("Email") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = vm.password,
        onValueChange = { vm.password = it; vm.clearError() },
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = vm.profileName,
        onValueChange = { vm.profileName = it },
        label = { Text("Profile name (optional)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))

    vm.errorMessage?.let { err ->
        Text(err, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.register() },
            enabled = !vm.isLoading,
            modifier = Modifier.weight(1f),
        ) {
            if (vm.isLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Create account")
        }
        OutlinedButton(
            onClick = { vm.showLogin() },
            modifier = Modifier.weight(1f),
        ) { Text("Sign in") }
    }
}

@Composable
private fun ConfirmRegisterForm(vm: AuthViewModel) {
    Text(
        "Check your email for a 6-digit confirmation code.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    OutlinedTextField(
        value = vm.confirmToken,
        onValueChange = { vm.confirmToken = it; vm.clearError() },
        label = { Text("Confirmation code") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(Modifier.height(8.dp))

    vm.errorMessage?.let { err ->
        Text(err, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.confirmRegistration() },
            enabled = !vm.isLoading,
            modifier = Modifier.weight(1f),
        ) {
            if (vm.isLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Confirm")
        }
        OutlinedButton(
            onClick = { vm.showLogin() },
            modifier = Modifier.weight(1f),
        ) { Text("Cancel") }
    }
}

@Composable
private fun SignedInSection(vm: AuthViewModel, state: AuthState.SignedIn) {
    // Signed-in header: email + active profile name.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Icon(Icons.Default.Person, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(state.email,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            if (state.activeProfile.name.isNotEmpty()) {
                Text("Profile: ${state.activeProfile.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // Sync button.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { vm.sync() },
            enabled = !vm.syncInProgress,
            modifier = Modifier.weight(1f),
        ) {
            if (vm.syncInProgress) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Sync, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text("Sync now")
        }
        OutlinedButton(
            onClick = { vm.logout() },
            modifier = Modifier.weight(1f),
        ) { Text("Sign out") }
    }

    vm.lastSyncResult?.let { result ->
        Text(result,
            style = MaterialTheme.typography.bodySmall,
            color = if (result == "Synced") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp))
    }

    // Profile list + create.
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Profiles", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(
            onClick = { vm.showCreateProfile = true },
            enabled = !vm.showCreateProfile,
        ) {
            Text("+ New", style = MaterialTheme.typography.labelSmall)
        }
    }
    Spacer(Modifier.height(4.dp))
    state.profiles.forEach { profile ->
        val isActive = profile.id == state.activeProfile.id
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            onClick = { if (!isActive) vm.switchProfile(profile.id) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(profile.name, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                if (isActive) {
                    Icon(Icons.Default.Check, contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    // Inline create-profile form.
    if (vm.showCreateProfile) {
        Spacer(Modifier.height(8.dp))
        val createErr = vm.createProfileError
        OutlinedTextField(
            value = vm.newProfileName,
            onValueChange = { vm.newProfileName = it; vm.createProfileError = null },
            label = { Text("New profile name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = createErr != null,
            supportingText = if (createErr != null) {
                { Text(createErr, color = MaterialTheme.colorScheme.error) }
            } else null,
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.createProfile() },
                enabled = vm.newProfileName.isNotBlank() && !vm.createProfileLoading,
                modifier = Modifier.weight(1f),
            ) {
                if (vm.createProfileLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create")
            }
            OutlinedButton(
                onClick = { vm.cancelCreateProfile() },
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Labelled ExposedDropdownMenuBox that immediately calls [onSelect] with the
 * chosen value — callers save immediately on selection, consistent with the
 * switch/toggle pattern used throughout this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,   // value → display label
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == value }?.second ?: value

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (v, l) ->
                    DropdownMenuItem(
                        text = { Text(l) },
                        onClick = { onSelect(v); expanded = false },
                    )
                }
            }
        }
    }
}
