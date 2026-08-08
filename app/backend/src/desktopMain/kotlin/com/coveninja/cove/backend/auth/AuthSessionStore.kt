package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.shared.network.CoveJson

class AuthSessionStore(
    private val database: CoveDatabase,
    private val now: () -> String,
) {
    fun get(): SupabaseSession? = database.coveQueries.selectAuthSession().executeAsOneOrNull()?.let {
        SupabaseSession(
            userId = it.user_id,
            email = it.email,
            accessToken = it.access_token,
            refreshToken = it.refresh_token,
            expiresAtEpochSeconds = it.expires_at,
        )
    }

    fun save(session: SupabaseSession) {
        require(session.userId.isNotBlank()) { "auth session requires a user ID" }
        require(session.accessToken.isNotBlank()) { "auth session requires an access token" }
        database.coveQueries.upsertAuthSession(
            session.userId,
            session.email,
            session.accessToken,
            session.refreshToken,
            session.expiresAtEpochSeconds,
            now(),
        )
    }

    fun clear() = database.coveQueries.deleteAuthSession()
}

class ClientSessionStore(
    private val database: CoveDatabase,
    private val now: () -> String,
) {
    fun get(): String? = database.coveQueries.selectClientSession().executeAsOneOrNull()

    fun save(json: String) {
        require(json.encodeToByteArray().size <= MAX_CLIENT_SESSION_BYTES) {
            "client session exceeds 1 MiB"
        }
        runCatching { CoveJson.parseToJsonElement(json) }
            .getOrElse { throw IllegalArgumentException("invalid JSON") }
        database.coveQueries.upsertClientSession(json, now())
    }

    fun clear() = database.coveQueries.deleteClientSession()
}

private const val MAX_CLIENT_SESSION_BYTES = 1 shl 20
