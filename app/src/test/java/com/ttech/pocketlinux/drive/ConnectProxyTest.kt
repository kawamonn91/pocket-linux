package com.ttech.pocketlinux.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import kotlin.concurrent.thread

class ConnectProxyTest {

    @Test
    fun parseRequest_CONNECTを読める() {
        val r = ConnectProxy.parseRequest("CONNECT www.googleapis.com:443 HTTP/1.1\r\nHost: www.googleapis.com:443\r\nProxy-Authorization: Basic abc=\r\n\r\n")!!
        assertEquals("www.googleapis.com", r.host)
        assertEquals(443, r.port)
        assertEquals("Basic abc=", r.authorization)
    }

    @Test
    fun parseRequest_IPv6の宛先も読める() {
        val r = ConnectProxy.parseRequest("CONNECT [2001:db8::1]:443 HTTP/1.1\r\n\r\n")!!
        assertEquals("2001:db8::1", r.host)
        assertNull(r.authorization)
    }

    @Test
    fun parseRequest_CONNECT以外や壊れたものはnull() {
        assertNull(ConnectProxy.parseRequest("GET http://example.com/ HTTP/1.1\r\n\r\n"))
        assertNull(ConnectProxy.parseRequest("CONNECT example.com HTTP/1.1\r\n\r\n"))
        assertNull(ConnectProxy.parseRequest("CONNECT example.com:99999 HTTP/1.1\r\n\r\n"))
        assertNull(ConnectProxy.parseRequest("CONNECT a/b:443 HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun 合言葉があれば中継し_無ければ断る() {
        // 受け取った行をそのまま返す相手
        val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
        ConnectProxy(allowedPorts = setOf(echo.localPort)).use { proxy ->
            val url = URI(proxy.url)
            val auth = "Basic " + Base64.getEncoder().encodeToString(url.userInfo.toByteArray())

            Socket("127.0.0.1", url.port).use { s ->
                s.getOutputStream().write("CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\nProxy-Authorization: $auth\r\n\r\n".toByteArray())
                val reader = s.getInputStream().bufferedReader()
                assertEquals("HTTP/1.1 200 Connection established", reader.readLine())
                assertEquals("", reader.readLine())
                s.getOutputStream().write("hello\n".toByteArray())
                assertEquals("hello", reader.readLine())
            }

            assertEquals("HTTP/1.1 407 Proxy Authentication Required", statusOf(url.port, "CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n\r\n"))
            assertEquals("HTTP/1.1 403 Forbidden", statusOf(url.port, "CONNECT 127.0.0.1:22 HTTP/1.1\r\nProxy-Authorization: $auth\r\n\r\n"))
        }
        echo.close()
    }

    private fun statusOf(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(request.toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }
}
