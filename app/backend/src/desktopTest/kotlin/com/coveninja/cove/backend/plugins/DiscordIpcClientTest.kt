package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.network.CoveJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordIpcClientTest {
    @Test
    fun `handshakes sets and clears activity through documented frames`() {
        val transport = FakeDiscordTransport()
        val client = DiscordIpcClient("1234567890123456") { transport }
        val activity = buildJsonObject {
            put("type", 3)
            put("details", "Example movie")
            put("state", "Playing")
        }

        client.setActivity(activity)

        assertEquals(0, transport.writes[0].first)
        assertEquals(
            "1234567890123456",
            CoveJson.parseToJsonElement(transport.writes[0].second.decodeToString())
                .jsonObject["client_id"]?.jsonPrimitive?.content,
        )
        val set = CoveJson.parseToJsonElement(transport.writes[1].second.decodeToString()).jsonObject
        assertEquals("SET_ACTIVITY", set["cmd"]?.jsonPrimitive?.content)
        assertEquals(activity, set["args"]?.jsonObject?.get("activity"))

        client.clear()

        val clear = CoveJson.parseToJsonElement(transport.writes.last().second.decodeToString()).jsonObject
        assertEquals(JsonNull, clear["args"]?.jsonObject?.get("activity"))
        assertTrue(transport.closed)
    }

    private class FakeDiscordTransport : DiscordIpcTransport {
        val writes = mutableListOf<Pair<Int, ByteArray>>()
        var closed = false
        private var reads = 0

        override fun write(opcode: Int, payload: ByteArray) {
            writes += opcode to payload
        }

        override fun read(): Pair<Int, ByteArray> {
            if (reads++ == 0) {
                return 1 to "{\"evt\":\"READY\"}".encodeToByteArray()
            }
            val nonce = CoveJson.parseToJsonElement(writes.last().second.decodeToString())
                .jsonObject["nonce"]?.jsonPrimitive?.content
            return 1 to buildJsonObject { put("nonce", nonce) }.toString().encodeToByteArray()
        }

        override fun close() {
            closed = true
        }
    }
}
