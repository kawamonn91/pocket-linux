package com.ttech.pocketlinux.drive

import com.ttech.pocketlinux.engine.JvmFileOps
import com.ttech.pocketlinux.engine.RootfsSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HardLinkGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** proot の --link2symlink がハードリンクを作った後と同じ形（コンテナの .l2s を指すリンクの連なり）を作る */
    private fun hardLinkedFile(drive: File, name: String, content: String): File {
        val l2s = tmp.root.resolve("containers/8b0b43ae/rootfs/.l2s").apply { mkdirs() }
        val final = File(l2s, ".l2s.${name}0001.0002").apply { writeText(content) }
        val intermediate = File(l2s, ".l2s.${name}0001")
        val link = File(drive, name)
        try {
            Files.createSymbolicLink(intermediate.toPath(), final.toPath())
            Files.createSymbolicLink(link.toPath(), intermediate.toPath())
        } catch (e: Exception) {
            assumeTrue("この環境ではシンボリックリンクを作れない: $e", false)
        }
        return link
    }

    @Test
    fun リンクを中身のコピーに戻す() {
        val drive = tmp.newFolder("drive", "app")
        val link = hardLinkedFile(drive, "hello.py", "print(1)\n")
        val other = File(drive, "README.txt").apply { writeText("普通のファイル") }

        assertEquals(1, HardLinkGuard.materialize(drive))
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("print(1)\n", link.readText())
        assertEquals("普通のファイル", other.readText())
        assertEquals(0, HardLinkGuard.materialize(drive))  // 2 回目は何もしない
    }

    @Test
    fun 下のフォルダも戻すが_飛ばすフォルダには入らない() {
        val drive = tmp.newFolder("drive")
        val inSrc = hardLinkedFile(File(drive, "src").apply { mkdirs() }, "a.txt", "a")
        val inModules = hardLinkedFile(File(drive, "node_modules").apply { mkdirs() }, "b.txt", "b")

        assertEquals(1, HardLinkGuard.materialize(drive) { it.name == "node_modules" })
        assertFalse(Files.isSymbolicLink(inSrc.toPath()))
        assertTrue(Files.isSymbolicLink(inModules.toPath()))
    }

    @Test
    fun ふつうのリンクと_指す先が無いリンクはそのまま() {
        val drive = tmp.newFolder("drive")
        val target = tmp.newFile("target.txt")
        val normal = File(drive, "normal")
        val broken = File(drive, "broken")
        try {
            Files.createSymbolicLink(normal.toPath(), target.toPath())
            Files.createSymbolicLink(broken.toPath(), tmp.root.toPath().resolve("containers/x/rootfs/.l2s/.l2s.gone0001"))
        } catch (e: Exception) {
            assumeTrue("この環境ではシンボリックリンクを作れない: $e", false)
        }
        assertEquals(0, HardLinkGuard.materialize(drive))
        assertTrue(Files.isSymbolicLink(normal.toPath()))
        assertTrue(Files.isSymbolicLink(broken.toPath()))
    }

    @Test
    fun gitのハードリンクを止める設定を一度だけ足す() {
        val rootfs = tmp.newFolder("rootfs")
        val setup = RootfsSetup(JvmFileOps)
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/gitconfig").writeText("[user]\n\tname = test\n")

        setup.ensureGitConfig(rootfs)
        setup.ensureGitConfig(rootfs)

        val text = File(rootfs, "etc/gitconfig").readText()
        assertTrue(text.startsWith("[user]\n\tname = test\n"))
        assertEquals(1, Regex("createObject = rename").findAll(text).count())
    }
}
