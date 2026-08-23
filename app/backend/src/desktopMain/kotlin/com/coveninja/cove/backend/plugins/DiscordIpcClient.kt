package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.network.CoveJson
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class DiscordIpcClient(
    private val applicationId: String,
    private val connector: () -> DiscordIpcTransport = ::connectDiscordIpc,
) : AutoCloseable {
    private var transport: DiscordIpcTransport? = null

    @Synchronized
    fun setActivity(activity: JsonElement) {
        val payload = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put("nonce", UUID.randomUUID().toString())
            put("args", buildJsonObject {
                put("pid", ProcessHandle.current().pid())
                put("activity", activity)
            })
        }
        request(payload)
    }

    @Synchronized
    fun clear() {
        val payload = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put("nonce", UUID.randomUUID().toString())
            put("args", buildJsonObject {
                put("pid", ProcessHandle.current().pid())
                put("activity", JsonNull)
            })
        }
        runCatching { request(payload) }
        close()
    }

    private fun request(payload: JsonObject) {
        val active = ensureConnected()
        runCatching {
            active.write(OP_FRAME, payload.toString().encodeToByteArray())
            readResponse(active, payload["nonce"]?.jsonPrimitive?.content)
        }.getOrElse { error ->
            close()
            throw IllegalStateException("Discord desktop is unavailable", error)
        }
    }

    private fun ensureConnected(): DiscordIpcTransport {
        transport?.let { return it }
        val opened = connector()
        runCatching {
            opened.write(
                OP_HANDSHAKE,
                buildJsonObject {
                    put("v", 1)
                    put("client_id", applicationId)
                }.toString().encodeToByteArray(),
            )
            val ready = opened.read()
            require(ready.first == OP_FRAME) { "Discord rejected the IPC handshake" }
            val payload = CoveJson.parseToJsonElement(ready.second.decodeToString()).jsonObject
            require(payload["evt"]?.jsonPrimitive?.content == "READY") { "Discord IPC did not become ready" }
        }.onFailure {
            opened.close()
            throw it
        }
        transport = opened
        return opened
    }

    private fun readResponse(active: DiscordIpcTransport, nonce: String?) {
        repeat(20) {
            val (opcode, bytes) = active.read()
            when (opcode) {
                OP_PING -> active.write(OP_PONG, bytes)
                OP_CLOSE -> throw IllegalStateException("Discord closed the IPC connection")
                OP_FRAME -> {
                    val payload = CoveJson.parseToJsonElement(bytes.decodeToString()).jsonObject
                    if (payload["evt"]?.jsonPrimitive?.content == "ERROR") {
                        val message = payload["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        throw IllegalStateException(message ?: "Discord rejected the activity")
                    }
                    if (nonce == null || payload["nonce"]?.jsonPrimitive?.content == nonce) return
                }
            }
        }
        throw IllegalStateException("Discord IPC response timed out")
    }

    @Synchronized
    override fun close() {
        transport?.close()
        transport = null
    }

    private companion object {
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1
        const val OP_CLOSE = 2
        const val OP_PING = 3
        const val OP_PONG = 4
    }
}

internal interface DiscordIpcTransport : Closeable {
    fun write(opcode: Int, payload: ByteArray)
    fun read(): Pair<Int, ByteArray>
}

private class StreamDiscordIpcTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: Closeable,
) : DiscordIpcTransport {
    override fun write(opcode: Int, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "Discord IPC frame is too large" }
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(opcode).putInt(payload.size).array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    override fun read(): Pair<Int, ByteArray> {
        val header = input.readExactly(8)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = buffer.int
        val length = buffer.int
        require(length in 0..MAX_FRAME_BYTES) { "Discord IPC frame has an invalid length" }
        return opcode to input.readExactly(length)
    }

    override fun close() = closer.close()

    private companion object {
        const val MAX_FRAME_BYTES = 1024 * 1024
    }
}

private fun connectDiscordIpc(): DiscordIpcTransport {
    return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        connectWindowsDiscordIpc()
    } else {
        connectUnixDiscordIpc()
    }
}

private fun connectWindowsDiscordIpc(): DiscordIpcTransport {
    var last: Throwable? = null
    for (index in 0..9) {
        for (prefix in listOf("\\\\.\\pipe\\", "\\\\?\\pipe\\")) {
            val file = runCatching { RandomAccessFile("${prefix}discord-ipc-$index", "rw") }
                .onFailure { last = it }
                .getOrNull() ?: continue
            return StreamDiscordIpcTransport(file.inputStream(), file.outputStream(), file)
        }
    }
    throw IllegalStateException("Discord IPC pipe was not found", last)
}

private fun connectUnixDiscordIpc(): DiscordIpcTransport {
    var last: Throwable? = null
    val prefixes = listOfNotNull(
        System.getenv("XDG_RUNTIME_DIR"),
        System.getenv("TMPDIR"),
        System.getenv("TMP"),
        System.getenv("TEMP"),
        "/tmp",
    ).distinct()
    for (prefix in prefixes) {
        for (index in 0..9) {
            val path = Path.of(prefix).resolve("discord-ipc-$index")
            if (!Files.exists(path)) continue
            val channel = runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(UnixDomainSocketAddress.of(path))
                }
            }.onFailure { last = it }.getOrNull() ?: continue
            return StreamDiscordIpcTransport(
                Channels.newInputStream(channel),
                Channels.newOutputStream(channel),
                channel,
            )
        }
    }
    throw IllegalStateException("Discord IPC socket was not found", last)
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) throw EOFException("Discord IPC closed unexpectedly")
        offset += count
    }
    return result
}

private fun RandomAccessFile.inputStream(): InputStream = object : InputStream() {
    override fun read(): Int = this@inputStream.read()
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        this@inputStream.read(bytes, offset, length)
}

private fun RandomAccessFile.outputStream(): OutputStream = object : OutputStream() {
    override fun write(value: Int) = this@outputStream.write(value)
    override fun write(bytes: ByteArray, offset: Int, length: Int) =
        this@outputStream.write(bytes, offset, length)
}
