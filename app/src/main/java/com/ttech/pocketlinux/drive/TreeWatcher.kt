package com.ttech.pocketlinux.drive

import android.os.FileObserver
import com.ttech.pocketlinux.engine.AndroidFileOps
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * フォルダの中の変更を、下のフォルダも含めて見張る。
 * Android の FileObserver は1階層だけを見るので、フォルダごとに付け、増えたフォルダにも付け足す。
 * [skip] が true のフォルダ（node_modules など）には入らない。
 */
class TreeWatcher(
    private val root: File,
    private val skip: (File) -> Boolean,
    private val onChange: () -> Unit,
) {
    private val observers = ConcurrentHashMap<String, FileObserver>()

    fun start() {
        root.mkdirs()
        addTree(root)
    }

    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun addTree(dir: File) {
        if (dir != root && skip(dir)) return
        watch(dir)
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory && !AndroidFileOps.isSymlink(child)) addTree(child)
        }
    }

    private fun watch(dir: File) {
        observers.computeIfAbsent(dir.path) {
            // File を受け取るコンストラクタは API 29 から。minSdk 26 のためパス文字列版を使う
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, MASK) {
                override fun onEvent(event: Int, path: String?) {
                    when (event and ALL_EVENTS) {
                        DELETE_SELF, MOVE_SELF -> observers.remove(dir.path)?.stopWatching()
                        CREATE, MOVED_TO -> path?.let { File(dir, it) }
                            ?.takeIf { it.isDirectory && !AndroidFileOps.isSymlink(it) }
                            ?.let { addTree(it) }
                    }
                    onChange()
                }
            }.also { it.startWatching() }
        }
    }

    private companion object {
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }
}
