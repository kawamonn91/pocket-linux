package com.ttech.pocketlinux.engine

import android.content.Context
import java.io.File

/**
 * アプリ内のファイルの置き場所。コンテナの中身や Drive の同期フォルダは VM のデータ用ディスク（data.img）の中にある。
 *
 * ```
 * files/
 *   vm/images/                     カーネルなど（APK から展開。VmImages）
 *   vm/data.img                    データ用ディスク（VM の /data。コンテナ・Drive の同期フォルダ）
 *   vm/agent.sock・qmp.sock        VM との通信
 *   vm/console.log・qemu.log       起動ログ
 *   containers/<id>/container.json コンテナの設定（中身は VM の /data/containers/<id>）
 *   images/blobs/<sha256>          pull したレイヤーのキャッシュ
 *   rclone/                        同期する組（pairs.json）と、同期しないもの（excludes.txt）
 * ```
 *
 * 1.x（proot）が残したものは [LegacyMigration] が VM へ移して消す:
 * `containers/<id>/rootfs`・`drive/`・`rclone/rclone.conf`・`rclone/bisync/`・`bridge/`・`fakeproc/`・`pocket-dev/`
 */
class Paths(
    val filesDir: File,
    val cacheDir: File,
    /** APK から展開された実行ファイル（libqemu.so）の場所 */
    val nativeLibDir: File,
) {
    constructor(context: Context) : this(
        context.filesDir,
        context.cacheDir,
        File(context.applicationInfo.nativeLibraryDir),
    )

    val containersDir = File(filesDir, "containers")
    val blobsDir = File(filesDir, "images/blobs")
    val rcloneDir = File(filesDir, "rclone")

    val qemu = File(nativeLibDir, "libqemu.so")
    val vmDir = File(filesDir, "vm")
    val vmImagesDir = File(vmDir, "images")
    val kernel = File(vmImagesDir, "Image")
    val systemImage = File(vmImagesDir, "system.erofs")
    val dataTemplate = File(vmImagesDir, "data.img.gz")
    val vmData = File(vmDir, "data.img")
    val vmAgentSocket = File(vmDir, "agent.sock")
    val vmQmpSocket = File(vmDir, "qmp.sock")
    val vmConsoleLog = File(vmDir, "console.log")
    val vmQemuLog = File(vmDir, "qemu.log")
    val vmPidFile = File(vmDir, "qemu.pid")

    /** 1.x のコンテナの中身（移行するまで残っている） */
    fun legacyRootfs(id: String) = File(containerDir(id), "rootfs")
    val legacyDriveDir = File(filesDir, "drive")
    val legacyRcloneConfig = File(rcloneDir, "rclone.conf")
    val legacyLeftovers = listOf(
        File(filesDir, "bridge"),
        File(filesDir, "fakeproc"),
        File(filesDir, "pocket-dev"),
        File(rcloneDir, "bisync"),
        File(rcloneDir, "cache"),
        File(cacheDir, "proot-tmp"),
        File(cacheDir, "rclone-tmp"),
    )

    fun containerDir(id: String) = File(containersDir, id)
    fun containerConfig(id: String) = File(containerDir(id), "container.json")

    fun ensureBaseDirs() {
        listOf(containersDir, blobsDir, rcloneDir, vmDir).forEach { it.mkdirs() }
    }
}
