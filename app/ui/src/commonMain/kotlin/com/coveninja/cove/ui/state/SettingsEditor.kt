package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// PUT /api/settings is a whole-object replace with no partial merge — any field
// omitted from the body is written back as its Go zero value. The transform
// receives the full current object as its receiver so all fields are always
// present in the result; callers cannot accidentally drop a field.
@Stable
class SettingsEditor(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val current: AppSettings,
) {
    fun edit(transform: AppSettings.() -> AppSettings) {
        scope.launch { graph.settings.update(current.transform()) }
    }
}

@Composable
fun rememberSettingsEditor(current: AppSettings): SettingsEditor {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    return remember(current) { SettingsEditor(graph, scope, current) }
}
