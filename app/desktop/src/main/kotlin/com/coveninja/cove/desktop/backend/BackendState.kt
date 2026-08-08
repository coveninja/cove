package com.coveninja.cove.desktop.backend

sealed interface BackendState {
    data object Starting : BackendState
    data object Ready : BackendState
    data class Failed(val message: String) : BackendState
    // Exit code 42 is a self-update sentinel: the binary replaced itself and
    // wants the shell to re-exec so the new version loads. Not a crash — the
    // crash budget is never touched.
    data object RestartRequested : BackendState
}
