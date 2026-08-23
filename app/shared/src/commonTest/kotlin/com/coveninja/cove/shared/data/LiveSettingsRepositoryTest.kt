package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.network.CoveApi
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

// MockEngine completes on a dispatcher runTest's scheduler does not drive, so
// advanceUntilIdle() returns while a load is still in flight. Suspending on the
// state transition is both correct and free of arbitrary timeouts — runTest
// fails the test if it never arrives.
private suspend fun StateFlow<SettingsState>.awaitReady(): SettingsState.Ready =
    first { it is SettingsState.Ready } as SettingsState.Ready

// Deliberately the production config: a divergent local Json here is how a
// real serialization bug stayed hidden.
private val testJson = CoveJson

private fun MockRequestHandleScope.jsonResponse(body: String) = respond(
    content = body,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

// Every field set to a non-default value so a missing or zeroed field in the
// PUT body is immediately detectable.
private val settingsJson = """
{
  "openOnMute": true,
  "defaultVolume": 0.8,
  "autoPlay": true,
  "rememberPosition": false,
  "defaultProvider": "nuvio",
  "autoSelectStream": true,
  "streamSelectionMode": "best",
  "measuredBandwidthMbps": 100.0,
  "sourcePreference": "hdr",
  "subtitlesEnabled": true,
  "defaultSubtitleLang": "fr",
  "defaultAudioLang": "de",
  "subtitleSize": 120.0,
  "subtitlePosition": 5.0,
  "subtitleBackground": false,
  "uiLanguage": "es",
  "showStreamDetails": false,
  "hideSpoilers": true,
  "autoSkipIntro": true,
  "autoSkipRecap": false,
  "autoSkipCredits": true,
  "autoSkipPreview": false,
  "onboardingDone": true,
  "discoveryAlgorithm": "custom",
  "customAlgorithmUrl": "https://example.com/algo",
  "prefetchStreams": false,
  "prefetchNextEpisode": false,
  "allowUploading": false,
  "probeStreams": false,
  "manageYtDlp": false,
  "seekStepSeconds": 30.0,
  "hardwareDecoding": false,
  "rememberVolume": false,
  "remoteAccessEnabled": true,
  "remoteAccessToken": "deadbeefcafe",
  "allowLanStreamSources": true,
  "addonsFollowPrimary": true,
  "traktScrobbleEnabled": false,
  "traktSyncEnabled": true,
  "autoSyncEnabled": false,
  "updatedAt": "2026-08-05T01:02:03Z"
}
""".trimIndent()

class LiveSettingsRepositoryTest {

    // This test would have caught a real data-loss bug: if update() serialized
    // only the changed field (or a fresh AppSettings()) instead of the full
    // object, every other setting would be zeroed on the backend side because
    // PUT /api/settings is a whole-object replace with no server-side merge.
    //
    // Mutation applied to verify: changed update() to send AppSettings() (all
    // Kotlin defaults) instead of the caller-supplied settings → test failed
    // because defaultProvider was "" instead of "nuvio" and discoveryAlgorithm
    // was "smart" instead of "custom".
    @Test
    fun `changing one field sends every other field at its original value`() = runTest {
        var capturedPutBody: String? = null

        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Get -> jsonResponse(settingsJson)
                HttpMethod.Put -> {
                    // toByteArray() is the suspend extension from MockUtilsKt;
                    // it handles all OutgoingContent variants.
                    capturedPutBody = request.body.toByteArray().decodeToString()
                    jsonResponse(capturedPutBody!!)
                }
                else -> error("Unexpected ${request.method} ${request.url}")
            }
        }) {
            install(ContentNegotiation) { json(testJson) }
        }

        val api  = CoveApi(client, CoveApiConfig())
        val repo = LiveSettingsRepository(api, this)

        // Await the state transition rather than advanceUntilIdle(): MockEngine
        // completes on a dispatcher the test scheduler does not drive, so
        // advancing virtual time returns while the load is still in flight.
        val current = repo.settings.awaitReady().settings
        // update() is a suspend call, so the PUT has completed once it returns.
        repo.update(current.copy(defaultVolume = 0.5))  // change exactly one field

        assertNotNull(capturedPutBody, "No PUT request was captured")

        // Decode the actual wire body — asserts on the real serialized bytes,
        // not on an in-memory Kotlin object that could be constructed differently.
        val sent = testJson.decodeFromString<AppSettings>(capturedPutBody!!)

        assertEquals(0.5,    sent.defaultVolume,       "defaultVolume (the changed field)")
        assertEquals(true,   sent.openOnMute,           "openOnMute")
        assertEquals(true,   sent.autoPlay,             "autoPlay")
        assertEquals(false,  sent.rememberPosition,     "rememberPosition")
        assertEquals("nuvio",sent.defaultProvider,      "defaultProvider")
        assertEquals(true,   sent.autoSelectStream,     "autoSelectStream")
        assertEquals("best", sent.streamSelectionMode,  "streamSelectionMode")
        assertEquals("fr",   sent.defaultSubtitleLang,  "defaultSubtitleLang")
        assertEquals("de",   sent.defaultAudioLang,     "defaultAudioLang")
        assertEquals(true,   sent.subtitlesEnabled,     "subtitlesEnabled")
        assertEquals(true,   sent.hideSpoilers,         "hideSpoilers")
        assertEquals(true,   sent.autoSkipIntro,        "autoSkipIntro")
        assertEquals(true,   sent.autoSkipCredits,      "autoSkipCredits")
        assertEquals(true,   sent.onboardingDone,       "onboardingDone")
        assertEquals("custom",sent.discoveryAlgorithm,  "discoveryAlgorithm")
        assertEquals("https://example.com/algo", sent.customAlgorithmUrl, "customAlgorithmUrl")

        // Fields with no UI yet. These are the ones a partial model silently
        // zeroes, and losing them is destructive rather than cosmetic: seeding
        // turns off, paired remote devices stop authenticating, Trakt scrobbling
        // stops. Asserted explicitly so shrinking AppSettings breaks the build.
        assertEquals(false, sent.prefetchStreams,       "prefetchStreams")
        assertEquals(false, sent.prefetchNextEpisode,   "prefetchNextEpisode")
        assertEquals(false, sent.allowUploading,        "allowUploading")
        assertEquals(false, sent.probeStreams,          "probeStreams")
        // Also non-default on purpose: each of these defaults to true (or to 10.0),
        // so the stored value is what proves the preference survived the round trip
        // rather than being re-derived from the Kotlin default.
        assertEquals(false, sent.manageYtDlp,           "manageYtDlp")
        assertEquals(30.0,  sent.seekStepSeconds,       "seekStepSeconds")
        assertEquals(false, sent.hardwareDecoding,      "hardwareDecoding")
        assertEquals(false, sent.rememberVolume,        "rememberVolume")
        assertEquals(true,  sent.remoteAccessEnabled,   "remoteAccessEnabled")
        assertEquals("deadbeefcafe", sent.remoteAccessToken, "remoteAccessToken")
        assertEquals(true,  sent.allowLanStreamSources, "allowLanStreamSources")
        // Non-default on purpose, and destructive to lose: zeroing it takes the
        // household's shared provider addons away from every secondary profile.
        assertEquals(true,  sent.addonsFollowPrimary,   "addonsFollowPrimary")
        assertEquals(false, sent.traktScrobbleEnabled,  "traktScrobbleEnabled")
        assertEquals(true,  sent.traktSyncEnabled,      "traktSyncEnabled")
        // Non-default on purpose: autoSyncEnabled defaults to true, so a value of
        // false is what proves the stored preference survived the round trip
        // rather than being re-derived from the Kotlin default.
        assertEquals(false, sent.autoSyncEnabled,       "autoSyncEnabled")

        // The general invariant behind all the assertions above: whatever keys
        // the server sent us must come back. Any field the model does not know
        // about is dropped by ignoreUnknownKeys on read and then absent on
        // write, which the backend persists as a default value. Comparing key
        // sets catches that for fields nobody has thought to assert yet —
        // including ones added to the persisted contract in future.
        val sentKeys     = testJson.parseToJsonElement(capturedPutBody!!).jsonObject.keys
        val receivedKeys = testJson.parseToJsonElement(settingsJson).jsonObject.keys
        assertEquals(
            receivedKeys, sentKeys,
            "PUT body lost keys the GET returned; they will be zeroed server-side",
        )
    }

    // Mutation applied to verify: removed try/catch in load() so the thrown
    // exception propagated out of the coroutine instead of setting Failed →
    // test failed because state remained Loading (the coroutine died).
    @Test
    fun `500 from settings GET maps to SettingsState Failed with status in message`() = runTest {
        val client = HttpClient(MockEngine {
            respond("Internal Server Error", HttpStatusCode.InternalServerError)
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val api  = CoveApi(client, CoveApiConfig())
        val repo = LiveSettingsRepository(api, this)

        val state = repo.settings.first { it !is SettingsState.Loading }
        assertIs<SettingsState.Failed>(state)
        assertTrue(
            state.message.contains("500"),
            "Failed message should contain the status code, got: '${state.message}'",
        )
    }
}
