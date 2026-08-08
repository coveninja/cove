package com.coveninja.cove.backend.store

import com.coveninja.cove.backend.db.CoveDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveProfileSession(private val database: CoveDatabase) {
    private val _profileId = MutableStateFlow(requireActiveProfile())
    val profileId: StateFlow<String> = _profileId.asStateFlow()

    fun activate(profileId: String) {
        require(database.coveQueries.selectProfileById(profileId).executeAsOneOrNull() != null) {
            "unknown profile $profileId"
        }
        database.transaction {
            database.coveQueries.setActiveProfile(profileId)
        }
        _profileId.value = profileId
    }

    fun refresh() {
        _profileId.value = requireActiveProfile()
    }

    private fun requireActiveProfile(): String =
        requireNotNull(database.coveQueries.selectActiveProfileId().executeAsOneOrNull()) {
            "database has no active profile; run legacy migration first"
        }
}
