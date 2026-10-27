package com.ttech.pocketlinux.engine

import android.content.Context
import java.io.File

/**
 * アプリ内のファイルの置き場所。
 *
 * ```
 * files/
 *   containers/<id>/rootfs        コンテナの中身（/）
 *   containers/<id>/container.json
 *   images/blobs/<sha256>          pull したレイヤーのキャッシュ
 *   drive/                         Google Drive と同期するフォルダ（コンテナの /mnt/drive）
 *   bridge/                        コンテナ → アプリへの依頼（URL を開くなど。コンテナの /run/pocket-linux）
 *   fakeproc/                      Android が読ませない /proc の代わり
 *   pocket-dev/                    IDE のサーバー（コンテナの /opt/pocket-dev）
 *   rclone/                        rclone の設定と bisync の作業フォルダ
 * cache/proot-tmp/                 proot の一時ファイル
 * ```
 */
class Paths(
    val filesDir: File,
    val cacheDir: File,
    /** APK から展開された実行ファイル（libproot.so など）の場所 */
    val nativeLibDir: File,
) {
    constructor(context: Context) : this(
        context.filesDir,
        context.cacheDir,
        File(context.applicationInfo.nativeLibraryDir),
    )

    val containersDir = File(filesDir, "containers")
    val blobsDir = File(filesDir, "images/blobs")
    val driveDir = File(filesDir, "drive")
    val bridgeDir = File(filesDir, "bridge")
    val openUrlDir = File(bridgeDir, "open")
    val fakeProcDir = File(filesDir, "fakeproc")
    val pocketDevDir = File(filesDir, "pocket-dev")
    val rcloneDir = File(filesDir, "rclone")
    val prootTmpDir = File(cacheDir, "proot-tmp")

    val proot = File(nativeLibDir, "libproot.so")
    val prootLoader = File(nativeLibDir, "libproot-loader.so")
    val rclone = File(nativeLibDir, "librclone.so")

    fun containerDir(id: String) = File(containersDir, id)
    fun rootfs(id: String) = File(containerDir(id), "rootfs")
    fun containerConfig(id: String) = File(containerDir(id), "container.json")

    fun ensureBaseDirs() {
        listOf(containersDir, blobsDir, driveDir, openUrlDir, fakeProcDir, rcloneDir, prootTmpDir)
            .forEach { it.mkdirs() }
    }
}
