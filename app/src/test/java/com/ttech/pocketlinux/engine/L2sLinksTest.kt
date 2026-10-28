package com.ttech.pocketlinux.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class L2sLinksTest {

    private val rootfs = "/data/data/com.ttech.pocketlinux/files/containers/a7483163/rootfs"
    private val restored = "/data/data/com.ttech.pocketlinux/files/containers/5b0e1f22/rootfs"

    @Test
    fun toGuest_rootfsの中を指すリンクはコンテナ内のパスになる() {
        assertEquals("/.l2s/.l2s.perl0001", L2sLinks.toGuest(rootfs, "$rootfs/.l2s/.l2s.perl0001"))
        assertEquals("/root/t/.l2s.a0001.0002", L2sLinks.toGuest(rootfs, "$rootfs/root/t/.l2s.a0001.0002"))
    }

    @Test
    fun toGuest_それ以外のリンクは変えない() {
        assertEquals("usr/bin", L2sLinks.toGuest(rootfs, "usr/bin"))
        assertEquals("/etc/alternatives/vi", L2sLinks.toGuest(rootfs, "/etc/alternatives/vi"))
        // 名前の先頭が同じだけの別のフォルダ
        assertEquals("${rootfs}2/x", L2sLinks.toGuest(rootfs, "${rootfs}2/x"))
    }

    @Test
    fun toHost_ハードリンクの代わりのリンクは復元先のパスになる() {
        assertEquals("$restored/.l2s/.l2s.perl0001", L2sLinks.toHost(restored, "/.l2s/.l2s.perl0001"))
        assertEquals("$restored/.l2s/.l2s.perl0001.0002", L2sLinks.toHost(restored, "/.l2s/.l2s.perl0001.0002"))
    }

    @Test
    fun toHost_修正前のバックアップに入っている別コンテナのパスも直す() {
        assertEquals("$restored/var/lib/dpkg/.l2s.status0001", L2sLinks.toHost(restored, "$rootfs/var/lib/dpkg/.l2s.status0001"))
        assertEquals(
            "$restored/.l2s/.l2s.a0001",
            L2sLinks.toHost(restored, "/data/user/0/com.ttech.pocketlinux.debug/files/containers/e99ba39f/rootfs/.l2s/.l2s.a0001"),
        )
    }

    @Test
    fun toHost_普通のリンクは変えない() {
        assertEquals("/etc/alternatives/vi", L2sLinks.toHost(restored, "/etc/alternatives/vi"))
        assertEquals("../lib/x.so", L2sLinks.toHost(restored, "../lib/x.so"))
        assertEquals(".l2s.a0001", L2sLinks.toHost(restored, ".l2s.a0001"))
    }

    @Test
    fun 書き出してから復元すると元の形に戻る() {
        val original = "$rootfs/.l2s/.l2s.a0001.0003"
        val guest = L2sLinks.toGuest(rootfs, original)
        assertEquals("$restored/.l2s/.l2s.a0001.0003", L2sLinks.toHost(restored, guest))
        assertEquals(original, L2sLinks.toHost(rootfs, guest))
    }
}
