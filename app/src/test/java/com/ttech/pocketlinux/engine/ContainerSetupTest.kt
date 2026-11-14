package com.ttech.pocketlinux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun 選んだものをスクリプトの変数で渡す() {
        val all = ContainerSetup.packageInstallScript(SetupOptions.DEFAULT)
        assertTrue(all.startsWith("POCKET_TOOLS=1\nPOCKET_JAPANESE=1\nPOCKET_DEV=1\n"))
        val sudoOnly = ContainerSetup.packageInstallScript(SetupOptions.SUDO_ONLY)
        assertTrue(sudoOnly.startsWith("POCKET_TOOLS=0\nPOCKET_JAPANESE=0\nPOCKET_DEV=0\n"))
        val japanese = ContainerSetup.packageInstallScript(SetupOptions(japanese = true))
        assertTrue(japanese.startsWith("POCKET_TOOLS=0\nPOCKET_JAPANESE=1\nPOCKET_DEV=0\n"))
    }

    @Test
    fun スクリプトの本体をリソースから読む() {
        val script = ContainerSetup.packageInstallScript(SetupOptions.DEFAULT)
        assertTrue(script.contains("pkg_install sudo \$tools"))
        assertTrue(script.contains("language-pack-ja"))
        assertTrue(script.contains("default-jdk-headless"))
        assertFalse(script.contains("\r"))
        // Kotlin 側が読む行の頭が、スクリプトの出す行と合っている
        assertTrue(script.contains("echo \"!! 入れられませんでした: \$1\""))
        assertTrue(script.contains("echo \"== 完了\""))
    }

    @Test
    fun 入れられなかったものの行を読む() {
        assertEquals("Java", ContainerSetup.parseSkipped("!! 入れられませんでした: Java"))
        assertEquals("日本語環境", ContainerSetup.parseSkipped("!! 入れられませんでした: 日本語環境 "))
        assertNull(ContainerSetup.parseSkipped("== Java（JDK）を入れています"))
        assertNull(ContainerSetup.parseSkipped("!! 入れられませんでした: "))
    }

    @Test
    fun 進み具合の表示に選んだものを並べる() {
        assertEquals("sudo", SetupOptions.SUDO_ONLY.label())
        assertEquals("sudo、よく使うツール、日本語環境、C・Java・Python", SetupOptions.DEFAULT.label())
        assertEquals("sudo、日本語環境", SetupOptions(japanese = true).label())
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
