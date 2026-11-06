package com.ttech.pocketlinux.vm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ゲストの代わりにパイプの反対側で話して、docs/vm-protocol.md のフレームを確かめる */
class AgentChannelTest {
    private class Fixture(
        onEvent: (String, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ -> },
        onClosed: (Throwable?) -> Unit = {},
    ) {
        private val toGuest = PipedOutputStream()
        private val fromApp = DataInputStream(PipedInputStream(toGuest, 1 shl 20))
        private val fromGuest = PipedOutputStream()
        val guestOut = DataOutputStream(fromGuest)
        val channel = AgentChannel(PipedInputStream(fromGuest, 1 shl 20), toGuest, onEvent, onClosed).also { it.start() }

        fun readFrame(): Triple<Int, Int, ByteArray> {
            val length = fromApp.readInt()
            val type = fromApp.readUnsignedByte()
            val id = fromApp.readInt()
            val payload = ByteArray(length - 5)
            fromApp.readFully(payload)
            return Triple(type, id, payload)
        }

        fun send(type: Int, id: Int, payload: ByteArray = ByteArray(0)) {
            guestOut.writeInt(5 + payload.size)
            guestOut.writeByte(type)
            guestOut.writeInt(id)
            guestOut.write(payload)
            guestOut.flush()
        }
    }

    @Test
    fun HELLOとOPENのフレームを送る() {
        val f = Fixture()
        f.channel.hello(1234, listOf("8.8.8.8"))
        val (type, id, payload) = f.readFrame()
        assertEquals(1, type)
        assertEquals(0, id)
        assertEquals("""{"time":1234,"dns":["8.8.8.8"]}""", payload.decodeToString())

        val session = f.channel.open(OpenRequest(op = "get", path = "/etc/hosts"), object : GuestListener {})
        val (openType, openId, openPayload) = f.readFrame()
        assertEquals(2, openType)
        assertEquals(session.id, openId)
        assertEquals("""{"op":"get","path":"/etc/hosts"}""", openPayload.decodeToString())
    }

    @Test
    fun 標準入力は上限で分けて順に送りEOFはその後() {
        val f = Fixture()
        val session = f.channel.open(OpenRequest(op = "untar"), object : GuestListener {})
        f.readFrame() // OPEN
        val data = ByteArray(AgentChannel.MAX_PAYLOAD + 10) { it.toByte() }
        val writer = Thread {
            session.write(data)
            session.closeStdin()
        }.apply { start() }
        val (t1, _, p1) = f.readFrame()
        val (t2, _, p2) = f.readFrame()
        val (t3, _, p3) = f.readFrame()
        writer.join(5000)
        assertEquals(3, t1)
        assertEquals(AgentChannel.MAX_PAYLOAD, p1.size)
        assertEquals(3, t2)
        assertEquals(10, p2.size)
        assertArrayEquals(data, p1 + p2)
        assertEquals(4, t3)
        assertEquals(0, p3.size)
    }

    @Test
    fun 出力と終了を受け取り終了の後は呼ばない() {
        val f = Fixture()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val done = CountDownLatch(1)
        var exit: GuestExit? = null
        val session = f.channel.open(OpenRequest(op = "exec"), object : GuestListener {
            override fun onStdout(data: ByteArray, length: Int) { stdout.append(data.decodeToString(0, length)) }
            override fun onStderr(data: ByteArray, length: Int) { stderr.append(data.decodeToString(0, length)) }
            override fun onExit(exit2: GuestExit) { exit = exit2; done.countDown() }
        })
        f.send(3, session.id, "out".toByteArray())
        f.send(9, session.id, "err".toByteArray())
        f.send(8, session.id, """{"code":3,"signal":0}""".toByteArray())
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("out", stdout.toString())
        assertEquals("err", stderr.toString())
        assertEquals(3, exit?.code)
    }

    @Test
    fun イベントを受け取る() {
        val done = CountDownLatch(1)
        var url: String? = null
        val f = Fixture(onEvent = { type, body ->
            if (type == "open-url") {
                url = body["url"].toString().trim('"')
                done.countDown()
            }
        })
        f.send(10, 0, """{"type":"open-url","url":"https://example.com/"}""".toByteArray())
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("https://example.com/", url)
    }

    @Test
    fun 接続が切れたら全部のチャンネルを終わらせる() {
        val closed = CountDownLatch(1)
        val exited = CountDownLatch(1)
        var exit: GuestExit? = null
        val f = Fixture(onClosed = { closed.countDown() })
        f.channel.open(OpenRequest(op = "exec"), object : GuestListener {
            override fun onExit(exit2: GuestExit) { exit = exit2; exited.countDown() }
        })
        f.guestOut.close()
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(AgentChannel.CODE_DISCONNECTED, exit?.code)
        assertTrue(f.channel.closed)
        // 閉じた後に開いても、すぐ終わりを知らせる
        var late: GuestExit? = null
        f.channel.open(OpenRequest(op = "exec"), object : GuestListener {
            override fun onExit(exit2: GuestExit) { late = exit2 }
        })
        assertEquals(AgentChannel.CODE_DISCONNECTED, late?.code)
    }

    @Test
    fun 長さがおかしいフレームで切る() {
        val closed = CountDownLatch(1)
        var cause: Throwable? = null
        val f = Fixture(onClosed = { cause = it; closed.countDown() })
        f.guestOut.writeInt(3)
        f.guestOut.flush()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(cause is IOException)
    }

    @Test
    fun 閉じた後の送信は失敗する() {
        val f = Fixture()
        val session = f.channel.open(OpenRequest(op = "exec"), object : GuestListener {})
        f.channel.close()
        assertTrue(runCatching { session.resize(80, 24) }.isFailure)
        assertFalse(!f.channel.closed)
    }
}
