package com.coveninja.cove.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coveninja.cove.api.AddonEntry
import com.coveninja.cove.api.CoveApiClient
import kotlinx.coroutines.launch

class AddonsViewModel : ViewModel() {
    var addons by mutableStateOf<List<AddonEntry>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var newUrl by mutableStateOf("")
    var addInProgress by mutableStateOf(false)
    var addError by mutableStateOf<String?>(null)

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                addons = CoveApiClient.get("/addons")
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun toggleAddon(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val ok = CoveApiClient.patch("/addons?id=$id", """{"enabled":$enabled}""")
            if (ok) {
                addons = addons.map { if (it.id == id) it.copy(enabled = enabled) else it }
            }
        }
    }

    fun deleteAddon(id: String) {
        viewModelScope.launch {
            CoveApiClient.delete("/addons?id=$id")
            load()
        }
    }

    fun addAddon() {
        val url = newUrl.trim()
        if (url.isBlank()) return
        addInProgress = true
        addError = null
        viewModelScope.launch {
            try {
                val result = CoveApiClient.post<AddonEntry>("/addons", """{"url":"$url"}""")
                if (result != null) {
                    newUrl = ""
                    load()
                } else {
                    addError = "Failed to add addon — check the manifest URL"
                }
            } catch (e: Exception) {
                addError = e.message
            } finally {
                addInProgress = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(
    vm: AddonsViewModel = viewModel(),
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Addons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            // Add new addon by manifest URL
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Add addon", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = vm.newUrl,
                    onValueChange = { vm.newUrl = it; vm.addError = null },
                    label = { Text("Manifest URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = { vm.addAddon() },
                            enabled = vm.newUrl.isNotBlank() && !vm.addInProgress,
                        ) {
                            if (vm.addInProgress)
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else
                                Icon(Icons.Default.Add, contentDescription = "Add")
                        }
                    },
                )
                vm.addError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }

            HorizontalDivider()

            // Addon list
            when {
                vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                vm.error != null -> Box(Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center) {
                    Text(vm.error ?: "Unknown error", color = MaterialTheme.colorScheme.error)
                }
                vm.addons.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center) {
                    Text(
                        "No addons installed. Paste a Stremio manifest URL above to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.addons, key = { it.id }) { addon ->
                        AddonRow(
                            addon = addon,
                            onToggle = { vm.toggleAddon(addon.id, it) },
                            onDelete = { vm.deleteAddon(addon.id) },
                        )
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    addon: AddonEntry,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                addon.manifest.name.ifBlank { addon.id },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(addon.kind, style = MaterialTheme.typography.labelSmall) },
                )
                if (addon.source == "official") {
                    AssistChip(
                        onClick = {},
                        label = { Text("official", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            if (addon.manifest.description.isNotBlank()) {
                Text(
                    addon.manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Switch(
            checked = addon.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(start = 8.dp),
        )

        // Only user-added (stremio-sourced) addons can be deleted
        if (addon.source != "official") {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove addon") },
            text = { Text("Remove \"${addon.manifest.name.ifBlank { addon.id }}\"?") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
