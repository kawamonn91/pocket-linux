package com.ttech.pocketlinux.drive

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Drive と同期するフォルダの中で、コンテナがハードリンクを作ったファイルを普通のファイルに戻す。
 *
 * proot の --link2symlink は、ハードリンクを作ると元のファイルの中身をコンテナの rootfs/.l2s に移し、
 * 両方の名前をそこへのリンクにする。Drive のフォルダ（/mnt/drive）でそれが起きると、中身がコンテナの中に
 * 入ってしまい、そのコンテナを消したり別のコンテナから開いたりすると読めなくなる（同期で消えたと
 * 見なされるおそれもある）。そこで同期の前に、コンテナの .l2s を指すリンクを中身のコピーに置き換える
 * （Drive はハードリンクを表せないので、同期の上でも失うものは無い）。
 */
object HardLinkGuard {
    /** Pocket Linux のコンテナの、ハードリンクの代わりの隠しファイルを指すパス */
    private val CONTAINER_L2S = Regex("/containers/[^/]+/rootfs/.*\\.l2s\\.")

    /** [dir] の中の対象のリンクを普通のファイルに戻し、戻した数を返す。[skip] のフォルダには入らない */
    fun materialize(dir: File, skip: (File) -> Boolean = { false }): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { child ->
            val path = child.toPath()
            when {
                Files.isSymbolicLink(path) -> if (materializeLink(path)) count++
                child.isDirectory && !skip(child) -> count += materialize(child, skip)
            }
        }
        return count
    }

    private fun materializeLink(link: Path): Boolean {
        val target = Files.readSymbolicLink(link).toString()
        if (!CONTAINER_L2S.containsMatchIn(target)) return false
        val real = try {
            link.toRealPath()  // .l2s の中のリンクをたどった先の、中身のファイル
        } catch (_: IOException) {
            return false  // 指す先が無い（コンテナを消した後など）。何もしない
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return false
        val tmp = link.resolveSibling(".${link.fileName}.pocketlinux-tmp")
        Files.copy(real, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        // リンクを、中身のコピーで置き換える（名前の変更なので、途中で止まっても中途半端なファイルは残らない）
        Files.move(tmp, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true
    }
}
