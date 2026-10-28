package com.ttech.pocketlinux.drive

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * rclone（Go で書かれた Linux 版）のための、端末内だけで使う HTTP CONNECT プロキシ。
 *
 * Android には /etc/resolv.conf が無く、Go の名前解決が動かないため、宛先の名前解決と接続を
 * アプリ側（Android の名前解決）で行う。rclone には HTTPS_PROXY で [url] を渡す。
 * 127.0.0.1 だけで待ち受け、起動ごとに作る合言葉（Proxy-Authorization）が無い接続は断る
 * （同じ端末のほかのアプリに使わせない）。
 */
class ConnectProxy(
    /** 中継してよい宛先のポート。rclone が使うのは HTTPS だけ（単体テストでは差し替える） */
    private val allowedPorts: Set<Int> = setOf(443),
) : Closeable {

    private val user = "pocketlinux"
    private val password = randomToken()
    private var server: ServerSocket? = null

    /** rclone の HTTPS_PROXY に渡す URL（起動していなければ起動する） */
    @get:Synchronized
    val url: String
        get() {
            val s = server ?: start()
            return "http://$user:$password@127.0.0.1:${s.localPort}"
        }

    private fun start(): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "ConnectProxy", isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "ConnectProxy-conn", isDaemon = true) { handle(client) }
            }
        }
        return s
    }

    @Synchronized
    override fun close() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.use {
            client.soTimeout = HEAD_TIMEOUT_MS
            val head = readHead(client.getInputStream()) ?: return
            val request = parseRequest(head)
            val out = client.getOutputStream()
            when {
                request == null -> return reply(out, "400 Bad Request")
                request.authorization != expectedAuthorization() -> return reply(out, "407 Proxy Authentication Required")
                request.port !in allowedPorts -> return reply(out, "403 Forbidden")
            }
            val upstream = try {
                Socket().apply { connect(InetSocketAddress(request!!.host, request.port), CONNECT_TIMEOUT_MS) }
            } catch (e: IOException) {
                Log.w(TAG, "接続できません: ${request!!.host}:${request.port} (${e.message})")
                return reply(out, "502 Bad Gateway")
            }
            upstream.use {
                reply(out, "200 Connection established")
                client.soTimeout = 0
                val back = thread(name = "ConnectProxy-down", isDaemon = true) {
                    pipe(upstream.getInputStream(), out)
                    runCatching { client.shutdownOutput() }
                }
                pipe(client.getInputStream(), upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
                back.join()
            }
        }
    }

    private fun expectedAuthorization(): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun reply(out: OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: IOException) {
            // どちらかが切れたら終わり
        }
    }

    /** リクエストの先頭（空行まで）を読む。長すぎるものや途中で切れたものは null */
    private fun readHead(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEAD_BYTES) {
            val b = try {
                input.read()
            } catch (_: IOException) {
                return null
            }
            if (b < 0) return null
            bytes.write(b)
            matched = if (b.toChar() == END_OF_HEAD[matched]) matched + 1 else if (b.toChar() == '\r') 1 else 0
            if (matched == END_OF_HEAD.length) return bytes.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    data class Request(val host: String, val port: Int, val authorization: String?)

    companion object {
        private const val TAG = "ConnectProxy"
        private const val END_OF_HEAD = "\r\n\r\n"
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val HEAD_TIMEOUT_MS = 10_000
        private const val CONNECT_TIMEOUT_MS = 20_000

        /** `CONNECT host:port HTTP/1.1` とヘッダーを読む。CONNECT 以外は null */
        fun parseRequest(head: String): Request? {
            val lines = head.split("\r\n")
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size != 3 || parts[0] != "CONNECT" || !parts[2].startsWith("HTTP/1.")) return null
            val target = parts[1]
            val colon = target.lastIndexOf(':')
            if (colon <= 0) return null
            val host = target.substring(0, colon).removePrefix("[").removeSuffix("]")
            val port = target.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) return null
            val authorization = lines.drop(1)
                .firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            return Request(host, port, authorization)
        }

        private fun randomToken(): String {
            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
