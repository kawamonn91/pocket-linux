package com.ttech.pocketlinux.engine

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths as NioPaths
import java.nio.file.attribute.PosixFilePermission

/**
 * シンボリックリンクと権限の操作。Android では [AndroidFileOps]、単体テスト（PC の JVM）では [JvmFileOps] を使う。
 */
interface FileOps {
    fun isSymlink(file: File): Boolean
    fun readlink(file: File): String
    fun symlink(target: String, link: File)
    fun chmod(file: File, mode: Int)
    fun mkfifo(file: File, mode: Int)

    /** シンボリックリンクの先には入らずに消す（リンクはリンクだけを消す） */
    fun deleteTree(file: File) {
        if (isSymlink(file)) {
            file.delete()
            return
        }
        if (file.isDirectory) {
            // 消せない権限のフォルダがあっても中身を消せるようにする
            runCatching { chmod(file, 0b111_000_000) }
            file.listFiles()?.forEach { deleteTree(it) }
        }
        file.delete()
    }

    /** ファイルかリンクかフォルダが存在するか（壊れたリンクも存在とみなす） */
    fun existsNoFollow(file: File): Boolean = isSymlink(file) || file.exists()
}

object AndroidFileOps : FileOps {
    override fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    override fun readlink(file: File): String = Os.readlink(file.path)

    override fun symlink(target: String, link: File) = Os.symlink(target, link.path)

    override fun chmod(file: File, mode: Int) = Os.chmod(file.path, mode)

    override fun mkfifo(file: File, mode: Int) = Os.mkfifo(file.path, mode)
}

/** 単体テスト用。setuid などの特殊ビットは扱えない（rwx だけ反映する） */
object JvmFileOps : FileOps {
    override fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    override fun readlink(file: File): String = Files.readSymbolicLink(file.toPath()).toString().replace('\\', '/')

    override fun symlink(target: String, link: File) {
        Files.createSymbolicLink(link.toPath(), NioPaths.get(target))
    }

    override fun chmod(file: File, mode: Int) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val perms = mutableSetOf<PosixFilePermission>()
        val bits = PosixFilePermission.entries // OWNER_READ ... OTHERS_EXECUTE の順
        bits.forEachIndexed { index, permission ->
            if (mode and (1 shl (8 - index)) != 0) perms += permission
        }
        runCatching { Files.setPosixFilePermissions(file.toPath(), perms) }
    }

    override fun mkfifo(file: File, mode: Int) {
        throw UnsupportedOperationException("mkfifo")
    }
}
