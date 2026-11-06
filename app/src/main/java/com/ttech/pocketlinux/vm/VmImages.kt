package com.ttech.pocketlinux.vm

import android.content.res.AssetManager
import com.ttech.pocketlinux.engine.Paths
import java.io.File

/** APK に同梱したカーネルなどを、版（assets/vm/VERSION）が変わったときだけ files/vm/images に展開する */
object VmImages {
    private const val ROOT = "vm"

    /** APK の中の名前と、展開先の名前（.gz のアセットは AGP が勝手に展開するので、名前を変えて入れている） */
    private val FILES = listOf("Image" to "Image", "system.erofs" to "system.erofs", "data.img.gzip" to "data.img.gz")

    fun extract(assets: AssetManager, paths: Paths) {
        val bundled = assets.open("$ROOT/VERSION").bufferedReader().use { it.readText().trim() }
        val marker = File(paths.vmImagesDir, "VERSION")
        val current = marker.takeIf { it.isFile }?.readText()?.trim()
        if (current == bundled && FILES.all { File(paths.vmImagesDir, it.second).isFile }) return

        paths.vmImagesDir.mkdirs()
        marker.delete()
        for ((asset, name) in FILES) {
            val dest = File(paths.vmImagesDir, name)
            val tmp = File(paths.vmImagesDir, "$name.tmp")
            assets.open("$ROOT/$asset").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(dest)) throw VmException("VM の中身を展開できませんでした（$name）")
        }
        marker.writeText(bundled)
    }
}
