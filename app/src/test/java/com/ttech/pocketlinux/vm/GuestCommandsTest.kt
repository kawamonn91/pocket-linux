package com.ttech.pocketlinux.vm

import com.ttech.pocketlinux.engine.ContainerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestCommandsTest {
    private val config = ContainerConfig(id = "abc", name = "ubuntu", image = "ubuntu:24.04", createdAt = 0)

    @Test
    fun 一般ユーザーのログインはユーザー名を渡す() {
        val request = GuestCommands.login(config, asRoot = false)
        assertEquals("exec", request.op)
        assertEquals("abc", request.container)
        assertEquals("user", request.argv!!.last())
        assertEquals("/home/user", request.cwd)
        assertTrue("HOME=/home/user" in request.env!!)
        assertTrue("USER=user" in request.env!!)
    }

    @Test
    fun rootで開くとユーザー名を渡さない() {
        val request = GuestCommands.login(config, asRoot = true)
        assertEquals("", request.argv!!.last())
        assertEquals("/root", request.cwd)
        assertTrue("USER=root" in request.env!!)
    }

    @Test
    fun rootでログインするコンテナ() {
        val request = GuestCommands.login(config.copy(user = null, home = "/root"), asRoot = false)
        assertEquals("", request.argv!!.last())
        assertTrue("HOME=/root" in request.env!!)
    }

    @Test
    fun スクリプトは引数で渡す() {
        val request = GuestCommands.exec(config, "echo 'a b'", asRoot = false)
        assertEquals(listOf("user", "echo 'a b'"), request.argv!!.takeLast(2))
    }

    @Test
    fun DNSはIPv4だけでslirp自身のアドレスを除く() {
        assertEquals(listOf("192.168.1.1"), GuestCommands.dnsForGuest(listOf("2001:db8::1", "192.168.1.1")))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), GuestCommands.dnsForGuest(listOf("10.0.2.3")))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), GuestCommands.dnsForGuest(emptyList()))
    }
}
