package com.coveninja.cove.shared.network

import kotlinx.serialization.json.Json

// The single JSON configuration for every request and response. Tests use this
// same instance rather than building their own, so serialization behaviour under
// test is the behaviour that ships — a local test config diverging from the real
// one is how the encodeDefaults bug below stayed invisible.
val CoveJson: Json = Json {
    // Compatibility endpoints add fields freely and this client is not always rebuilt
    // in lockstep with it.
    ignoreUnknownKeys = true

    // Legacy compatibility responses may encode absent optional values as null.
    explicitNulls = false
    coerceInputValues = true

    // Load-bearing, and NOT the kotlinx default. Without it, any property whose
    // value happens to equal its Kotlin default is omitted from the request
    // body. PUT /api/settings is a whole-object replace with no server-side
    // merge, so an omitted field is persisted as its default value: a user whose
    // defaultVolume is genuinely 1.0 would have it written back as 0.0, and
    // rememberPosition=true would silently become false. The bug is invisible in
    // any test whose fixture avoids default-valued fields.
    encodeDefaults = true
}
