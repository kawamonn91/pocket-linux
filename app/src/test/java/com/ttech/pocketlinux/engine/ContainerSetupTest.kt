package com.ttech.pocketlinux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerSetupTest {

    @Test
    fun イメージのENVをprofileに書く() {
        val script = ContainerSetup.imageEnvScript(listOf("GOPATH=/go", "HOME=/root", "A='b'", "壊れた行"))
        assertTrue(script.contains("export GOPATH='/go'\n"))
        assertTrue(script.contains("export A=''\\''b'\\'''\n"))
        assertFalse(script.contains("HOME"))
        assertFalse(script.contains("壊れた行"))
    }

    @Test
    fun ツールを選ぶと一緒に入れる() {
        assertTrue(ContainerSetup.packageInstallScript(true).contains("--no-install-recommends sudo ca-certificates curl git"))
        assertFalse(ContainerSetup.packageInstallScript(false).contains("curl"))
    }

    @Test
    fun ホームフォルダの持ち主を一般ユーザーにする() {
        val script = ContainerSetup.homeScript("/home/user", 1000)
        assertTrue(script.contains("chown -Rh 1000:1000"))
        assertTrue(script.contains("/mnt/drive"))
    }

    @Test
    fun 移行の修理はユーザーが無ければ所有者を変えない() {
        assertTrue(ContainerSetup.legacyRepairScript("/home/user", 1000).contains("chown -Rh 1000:1000 '/home/user'"))
        assertFalse(ContainerSetup.legacyRepairScript("/root", null).contains("chown"))
    }

    @Test
    fun リンクの中身をコンテナの中のパスにする() {
        val rootfs = "/data/data/com.ttech.pocketlinux/files/containers/abc/rootfs"
        assertEquals("/.l2s/.l2s.x0001", LegacyMigration.toGuestLink(rootfs, "$rootfs/.l2s/.l2s.x0001"))
        assertEquals("/usr/bin/python3", LegacyMigration.toGuestLink(rootfs, "/usr/bin/python3"))
        assertEquals("../lib", LegacyMigration.toGuestLink(rootfs, "../lib"))
    }
}
