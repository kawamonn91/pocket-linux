package com.ttech.pocketlinux.engine

import java.io.File
import java.io.IOException

/**
 * rootfs の中のパスを、実際のファイルの場所に変換する。
 *
 * 途中にシンボリックリンクがあれば rootfs を `/` とみなして解決し、`..` や絶対パスのリンクでも
 * rootfs の外には出さない（chroot と同じ扱い）。悪意のあるイメージが `x -> /data/...` のような
 * リンクを使って、アプリの他のファイルを書き換えるのを防ぐ。
 */
object RootfsPath {
    private const val MAX_SYMLINK_HOPS = 40

    /**
     * @param path rootfs 内のパス（先頭の / はあってもなくてもよい）
     * @param followLast 最後の要素がリンクのとき、その先まで解決するか
     */
    fun resolve(root: File, path: String, ops: FileOps, followLast: Boolean): File {
        val queue = ArrayDeque(split(path))
        val resolved = ArrayList<String>()
        var hops = 0

        while (queue.isNotEmpty()) {
            val part = queue.removeFirst()
            when (part) {
                "", "." -> continue
                ".." -> {
                    if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                    continue
                }
            }
            val candidate = File(root, (resolved + part).joinToString("/"))
            val isLast = queue.isEmpty()
            if ((!isLast || followLast) && ops.isSymlink(candidate)) {
                if (++hops > MAX_SYMLINK_HOPS) throw IOException("シンボリックリンクが多すぎます: $path")
                val target = ops.readlink(candidate)
                if (target.startsWith("/")) resolved.clear()
                split(target).asReversed().forEach { queue.addFirst(it) }
                continue
            }
            resolved += part
        }
        return if (resolved.isEmpty()) root else File(root, resolved.joinToString("/"))
    }

    /**
     * tar のエントリ名を正規化する。`..` を含むものは null（展開しない）。
     * 先頭の `./` や `/` は取り除く。
     */
    fun normalizeEntryName(name: String): String? {
        val parts = split(name).filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun split(path: String): List<String> = path.split('/')
}
