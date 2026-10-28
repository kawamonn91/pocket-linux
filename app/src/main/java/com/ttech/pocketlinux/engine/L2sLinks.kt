package com.ttech.pocketlinux.engine

/**
 * proot の `--link2symlink` が作る、ハードリンクの代わりのリンク（`.l2s.名前0001` など）。
 *
 * 隠しファイルは `PROOT_L2S_DIR`（rootfs の [DIR]）にまとめる。指定しないと、ハードリンクを作った
 * フォルダ（Drive と同期する場所など）に置かれてしまう。フォルダが無いと `ln` が失敗する
 * （dpkg が perl を展開できなくなる）ので、起動の前に必ず作る。
 *
 * proot はリンクの中身に端末側の絶対パス（…/containers/<id>/rootfs/…）を書き、リンク数の管理も
 * そのパスで行う。コンテナ内のパスのままにすると、読めてもリンク数や `ln` が壊れる。そのため
 * バックアップではコンテナ内のパスにして持ち運べるようにし、復元するときに新しい rootfs のパスへ戻す。
 */
object L2sLinks {
    const val DIR = ".l2s"
    private const val NAME_PREFIX = ".l2s."

    /** Pocket Linux のコンテナの rootfs を指す、端末側の絶対パス（パッケージ名やコンテナ ID は問わない） */
    private val HOST_ROOTFS = Regex("^/.+/containers/[^/]+/rootfs(/.*)$")

    /** 書き出すとき: この rootfs の中を指すリンクを、コンテナ内のパスにする */
    fun toGuest(rootfs: String, target: String): String =
        if (target.startsWith("$rootfs/")) target.removePrefix(rootfs) else target

    /**
     * 復元するとき: ハードリンクの代わりのリンクを、復元先の rootfs の端末側のパスにする。
     * 修正前に書き出したバックアップ（別のコンテナの端末側のパスが入っている）も直す。
     */
    fun toHost(rootfs: String, target: String): String {
        val guest = HOST_ROOTFS.matchEntire(target)?.groupValues?.get(1) ?: target
        if (!guest.startsWith("/") || !guest.substringAfterLast('/').startsWith(NAME_PREFIX)) return target
        return rootfs + guest
    }
}
