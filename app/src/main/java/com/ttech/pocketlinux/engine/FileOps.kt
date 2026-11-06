package com.ttech.pocketlinux.engine

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * シンボリックリンクと権限を扱うファイル操作（1.x のコンテナや残骸を消すときに使う）。
 */
object AndroidFileOps {
    fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    /** シンボリックリンクの先には入らずに消す（リンクはリンクだけを消す） */
    fun deleteTree(file: File) {
        if (isSymlink(file)) {
            file.delete()
            return
        }
        if (file.isDirectory) {
            // 消せない権限のフォルダがあっても中身を消せるようにする
            runCatching { Os.chmod(file.path, 0b111_000_000) }
            file.listFiles()?.forEach { deleteTree(it) }
        }
        file.delete()
    }
}
