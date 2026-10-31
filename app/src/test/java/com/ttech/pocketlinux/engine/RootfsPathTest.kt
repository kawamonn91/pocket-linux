package com.ttech.pocketlinux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

class RootfsPathTest {

    private class FakeFileOps(private val symlinks: Map<String, String> = emptyMap()) : FileOps {
        override fun isSymlink(file: File): Boolean = symlinks.containsKey(normalize(file))
        override fun readlink(file: File): String = symlinks[normalize(file)] ?: throw IOException("Not a symlink: $file")
        override fun symlink(target: String, link: File) = Unit
        override fun chmod(file: File, mode: Int) = Unit
        override fun mkfifo(file: File, mode: Int) = Unit

        private fun normalize(file: File): String = file.path.replace('\\', '/')
    }

    @Test
    fun normalizeEntryName_正常なパスは正規化されて返る() {
        assertEquals("usr/bin/ls", RootfsPath.normalizeEntryName("usr/bin/ls"))
        assertEquals("usr/bin/ls", RootfsPath.normalizeEntryName("/usr/bin/ls"))
        assertEquals("usr/bin/ls", RootfsPath.normalizeEntryName("./usr/bin/ls"))
        assertEquals("usr/bin/ls", RootfsPath.normalizeEntryName("./usr/./bin//ls"))
        assertEquals("etc/hosts", RootfsPath.normalizeEntryName("///etc///hosts"))
    }

    @Test
    fun normalizeEntryName_ドットドットを含むものはnullを返す() {
        assertNull(RootfsPath.normalizeEntryName(".."))
        assertNull(RootfsPath.normalizeEntryName("../etc/passwd"))
        assertNull(RootfsPath.normalizeEntryName("etc/../passwd"))
        assertNull(RootfsPath.normalizeEntryName("etc/passwd/.."))
        assertNull(RootfsPath.normalizeEntryName("./a/../../b"))
    }

    @Test
    fun resolve_通常のパスとドットドットのrootfs内への閉じ込め() {
        val root = File("rootfs")
        val ops = FakeFileOps()

        // 単純な相対パスと絶対パス
        assertEquals(File(root, "etc/hosts"), RootfsPath.resolve(root, "etc/hosts", ops, followLast = false))
        assertEquals(File(root, "etc/hosts"), RootfsPath.resolve(root, "/etc/hosts", ops, followLast = false))

        // .. があっても rootfs の外には出ない
        assertEquals(File(root, "etc/hosts"), RootfsPath.resolve(root, "../../etc/hosts", ops, followLast = false))
        assertEquals(File(root, "etc/hosts"), RootfsPath.resolve(root, "var/log/../../etc/hosts", ops, followLast = false))
        assertEquals(root, RootfsPath.resolve(root, "../..", ops, followLast = false))
    }

    @Test
    fun resolve_絶対パスや相対パスのシンボリックリンクの解決() {
        val root = File("rootfs")
        val rootPath = root.path.replace('\\', '/')
        val ops = FakeFileOps(
            mapOf(
                "$rootPath/lib64" to "/usr/lib64",
                "$rootPath/bin/sh" to "dash",
                "$rootPath/escape" to "../../../etc/passwd",
            ),
        )

        // 絶対パスシンボリックリンク (lib64 -> /usr/lib64) が rootfs 内に閉じ込められる
        assertEquals(
            File(root, "usr/lib64/ld-linux.so"),
            RootfsPath.resolve(root, "lib64/ld-linux.so", ops, followLast = false),
        )

        // followLast = false のときは末尾のリンクは解決しない
        assertEquals(
            File(root, "bin/sh"),
            RootfsPath.resolve(root, "bin/sh", ops, followLast = false),
        )

        // followLast = true のときは末尾のリンクも解決する
        assertEquals(
            File(root, "bin/dash"),
            RootfsPath.resolve(root, "bin/sh", ops, followLast = true),
        )

        // シンボリックリンクで rootfs の外に出ようとしても rootfs 内に閉じ込められる
        assertEquals(
            File(root, "etc/passwd"),
            RootfsPath.resolve(root, "escape", ops, followLast = true),
        )
    }

    @Test
    fun resolve_40ホップを超えるシンボリックリンクループを検出して例外を投げる() {
        val root = File("rootfs")
        val rootPath = root.path.replace('\\', '/')
        val ops = FakeFileOps(
            mapOf(
                "$rootPath/loopA" to "loopB",
                "$rootPath/loopB" to "loopA",
            ),
        )

        val ex = assertThrows(IOException::class.java) {
            RootfsPath.resolve(root, "loopA", ops, followLast = true)
        }
        assertEquals(true, ex.message?.contains("シンボリックリンクが多すぎます"))
    }
}
