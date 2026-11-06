package com.ttech.pocketlinux.vm

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** OPEN の中身（docs/vm-protocol.md の op）。null の項目は送らない */
@Serializable
data class OpenRequest(
    val op: String,
    val container: String? = null,
    val hostname: String? = null,
    val argv: List<String>? = null,
    val env: List<String>? = null,
    val cwd: String? = null,
    val pty: Boolean? = null,
    val cols: Int? = null,
    val rows: Int? = null,
    val path: String? = null,
    val mode: Int? = null,
    val exclude: List<String>? = null,
    val listen: String? = null,
    val target: String? = null,
    val time: Long? = null,
    val servers: List<String>? = null,
)

@Serializable
data class GuestExit(val code: Int = 0, val signal: Int = 0, val error: String? = null)

/** チャンネルの受け口。読み取り用のスレッド（全チャンネルで 1 本）で呼ばれるので、長く止めないこと */
interface GuestListener {
    /** [data] は再利用されるので、必要ならコピーする */
    fun onStdout(data: ByteArray, length: Int) {}
    fun onStderr(data: ByteArray, length: Int) {}
    fun onExit(exit: GuestExit) {}
}

/**
 * pocket-agent との制御用チャンネル（docs/vm-protocol.md）。1 本のストリームの上で複数のチャンネルを動かす。
 *
 * 送るときは 2 つの列に分ける: ターミナルの入力や制御（急ぎ）は、tar の流し込みなど（まとめて送るもの）を追い越す。
 * まとめて送るものは列が埋まると呼び出し元を待たせる（これがゲストまでの流量の制御になる）。
 */
class AgentChannel(
    input: InputStream,
    output: OutputStream,
    private val onEvent: (String, JsonObject) -> Unit,
    private val onClosed: (Throwable?) -> Unit = {},
) {
    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val urgent = ArrayDeque<ByteArray>()
    private val bulk = ArrayDeque<ByteArray>()
    private val sessions = ConcurrentHashMap<Int, GuestListener>()
    private val nextId = AtomicInteger(1)

    @Volatile
    var closed = false
        private set

    fun start() {
        thread(name = "AgentChannelReader", isDaemon = true) { readLoop() }
        thread(name = "AgentChannelWriter", isDaemon = true) { writeLoop() }
    }

    fun hello(timeMillis: Long, dns: List<String>) {
        val body = """{"time":$timeMillis,"dns":${json.encodeToString(ListSerializerString, dns)}}"""
        sendUrgent(HELLO, 0, body.encodeToByteArray())
    }

    /** チャンネルを開く。OPEN の直後に write してよい */
    fun open(request: OpenRequest, listener: GuestListener): GuestSession {
        val id = nextId.getAndIncrement()
        sessions[id] = listener
        if (closed) {
            sessions.remove(id)
            listener.onExit(GuestExit(CODE_DISCONNECTED, error = DISCONNECTED))
            return GuestSession(id)
        }
        sendUrgent(OPEN, id, json.encodeToString(OpenRequest.serializer(), request).encodeToByteArray())
        return GuestSession(id)
    }

    inner class GuestSession internal constructor(val id: Int) {
        /** 標準入力へ（まとめて送るもの。列が埋まっていれば待つ） */
        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) = forEachFrame(data, offset, length) { part ->
            sendBulk(DATA, id, part)
        }

        /** ターミナルの入力（急ぎ。tar の流し込みなどを追い越す） */
        fun writeUrgent(data: ByteArray, offset: Int = 0, length: Int = data.size) = forEachFrame(data, offset, length) { part ->
            sendUrgent(DATA, id, part)
        }

        /** 標準入力を閉じる。それまでに write したものの後に届く */
        fun closeStdin() = sendBulk(EOF, id, EMPTY)

        fun resize(cols: Int, rows: Int) = sendUrgent(RESIZE, id, """{"cols":$cols,"rows":$rows}""".encodeToByteArray())

        fun signal(signal: Int) = sendUrgent(SIGNAL, id, """{"signal":$signal}""".encodeToByteArray())

        /** チャンネルを捨て、プロセスを止める。これ以降 listener は呼ばれない */
        fun close() {
            sessions.remove(id)
            runCatching { sendUrgent(CLOSE, id, EMPTY) }
        }
    }

    fun close() {
        finish(null)
    }

    // ---------------------------------------------------------------- 送る

    private inline fun forEachFrame(data: ByteArray, offset: Int, length: Int, send: (ByteArray) -> Unit) {
        var at = offset
        val end = offset + length
        while (at < end) {
            val n = minOf(end - at, MAX_PAYLOAD)
            send(data.copyOfRange(at, at + n))
            at += n
        }
    }

    private fun frame(type: Int, channel: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(9 + payload.size)
        val length = 5 + payload.size
        out[0] = (length ushr 24).toByte()
        out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        out[4] = type.toByte()
        out[5] = (channel ushr 24).toByte()
        out[6] = (channel ushr 16).toByte()
        out[7] = (channel ushr 8).toByte()
        out[8] = channel.toByte()
        payload.copyInto(out, 9)
        return out
    }

    private fun sendUrgent(type: Int, channel: Int, payload: ByteArray) {
        val bytes = frame(type, channel, payload)
        synchronized(lock) {
            if (closed) throw IOException(DISCONNECTED)
            urgent.addLast(bytes)
            lock.notifyAll()
        }
    }

    private fun sendBulk(type: Int, channel: Int, payload: ByteArray) {
        val bytes = frame(type, channel, payload)
        synchronized(lock) {
            while (!closed && bulk.size >= MAX_BULK_FRAMES) lock.wait()
            if (closed) throw IOException(DISCONNECTED)
            bulk.addLast(bytes)
            lock.notifyAll()
        }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (!closed && urgent.isEmpty() && bulk.isEmpty()) lock.wait()
                    if (closed) return
                    (urgent.pollFirst() ?: bulk.pollFirst()).also { lock.notifyAll() }
                }
                writer.write(next)
                writer.flush()
            }
        } catch (e: IOException) {
            finish(e)
        } catch (_: InterruptedException) {
            finish(null)
        }
    }

    // ---------------------------------------------------------------- 受ける

    private fun readLoop() {
        val buffer = ByteArray(MAX_PAYLOAD)
        try {
            while (true) {
                val length = reader.readInt()
                if (length < 5 || length > 5 + MAX_PAYLOAD) throw IOException("フレームの長さがおかしい: $length")
                val type = reader.readUnsignedByte()
                val channel = reader.readInt()
                val size = length - 5
                reader.readFully(buffer, 0, size)
                dispatch(type, channel, buffer, size)
            }
        } catch (e: IOException) {
            finish(e)
        }
    }

    private fun dispatch(type: Int, channel: Int, payload: ByteArray, size: Int) {
        when (type) {
            DATA -> sessions[channel]?.onStdout(payload, size)
            STDERR -> sessions[channel]?.onStderr(payload, size)
            EXIT -> {
                val exit = runCatching { json.decodeFromString(GuestExit.serializer(), payload.decodeToString(0, size)) }
                    .getOrDefault(GuestExit(code = 1, error = "EXIT の中身を読めません"))
                sessions.remove(channel)?.onExit(exit)
            }
            EVENT -> {
                val body = runCatching { json.parseToJsonElement(payload.decodeToString(0, size)).jsonObject }.getOrNull() ?: return
                val kind = body["type"]?.jsonPrimitive?.content ?: return
                onEvent(kind, body)
            }
        }
    }

    private fun finish(cause: Throwable?) {
        synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
        }
        runCatching { writer.close() }
        runCatching { reader.close() }
        val listeners = sessions.values.toList()
        sessions.clear()
        listeners.forEach { runCatching { it.onExit(GuestExit(CODE_DISCONNECTED, error = DISCONNECTED)) } }
        onClosed(cause)
    }

    companion object {
        private const val HELLO = 1
        private const val OPEN = 2
        private const val DATA = 3
        private const val EOF = 4
        private const val RESIZE = 5
        private const val SIGNAL = 6
        private const val CLOSE = 7
        private const val EXIT = 8
        private const val STDERR = 9
        private const val EVENT = 10

        const val MAX_PAYLOAD = 64 * 1024
        private const val MAX_BULK_FRAMES = 8
        const val CODE_DISCONNECTED = 255
        const val DISCONNECTED = "VM との接続が切れました"

        private val EMPTY = ByteArray(0)
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        private val ListSerializerString = ListSerializer(String.serializer())
    }
}
