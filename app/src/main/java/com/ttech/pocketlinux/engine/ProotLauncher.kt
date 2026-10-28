package com.ttech.pocketlinux.engine

import java.io.File

/** proot を起動するためのコマンドライン（実行ファイル・引数・環境変数） */
data class LaunchCommand(
    val executable: String,
    /** argv[0] を含む */
    val argv: List<String>,
    val env: List<String>,
    val cwd: String,
)

/** コンテナに追加で見せるもの */
data class LaunchOptions(
    /** 端末の共有ストレージ（/storage/emulated/0）を /mnt/storage に見せる（許可がある場合のみ） */
    val bindStorage: Boolean = false,
    /** Android が読ませない /proc の代わり (ホスト側, コンテナ内) */
    val fakeProc: List<Pair<String, String>> = emptyList(),
)

/**
 * proot の起動コマンドを組み立てる。proot-distro と同じ構成で、
 * root に見せかけた状態（-0）で起動し、一般ユーザーへは `su -l` で切り替える
 * （proot が setuid を再現するので、そのユーザーから sudo で root に戻れる）。
 */
class ProotLauncher(private val paths: Paths) {

    fun login(config: ContainerConfig, asRoot: Boolean, options: LaunchOptions): LaunchCommand {
        val rootfs = paths.rootfs(config.id)
        val shell = loginShell(rootfs)
        val tail = if (!asRoot && canSwitchUser(config, rootfs)) {
            listOf("su", "-l", config.user!!)
        } else {
            listOf(shell, "-l")
        }
        return build(config, asRoot, options, tail)
    }

    /** コマンドを1つ実行する（初期設定・自動実行・IDE のサーバーなど） */
    fun exec(config: ContainerConfig, script: String, asRoot: Boolean, options: LaunchOptions): LaunchCommand {
        val rootfs = paths.rootfs(config.id)
        val tail = if (!asRoot && canSwitchUser(config, rootfs)) {
            listOf("su", "-l", config.user!!, "-c", script)
        } else {
            listOf(loginShell(rootfs), "-lc", script)
        }
        return build(config, asRoot, options, tail)
    }

    private fun canSwitchUser(config: ContainerConfig, rootfs: File): Boolean =
        config.user != null && SU_PATHS.any { File(rootfs, it).exists() || AndroidFileOpsSafe.isSymlink(File(rootfs, it)) }

    private fun build(config: ContainerConfig, asRoot: Boolean, options: LaunchOptions, tail: List<String>): LaunchCommand {
        val rootfs = paths.rootfs(config.id)
        File(rootfs, L2sLinks.DIR).mkdirs() // 無いとハードリンクを作れない（L2sLinks を参照）
        val home = if (asRoot || config.user == null) "/root" else config.home
        val user = if (asRoot || config.user == null) "root" else config.user
        val args = buildArgs(
            proot = paths.proot.path,
            rootfs = rootfs.path,
            workDir = home,
            binds = binds(rootfs, options),
            guestEnv = guestEnv(config, home, user),
            command = tail,
        )
        return LaunchCommand(
            executable = paths.proot.path,
            argv = args,
            env = hostEnv(rootfs),
            cwd = paths.filesDir.path,
        )
    }

    private fun binds(rootfs: File, options: LaunchOptions): List<Pair<String, String>> {
        val list = mutableListOf(
            "/dev" to "/dev",
            "/proc" to "/proc",
            "/sys" to "/sys",
            "/dev/urandom" to "/dev/random",
            "/proc/self/fd" to "/dev/fd",
            "/proc/self/fd/0" to "/dev/stdin",
            "/proc/self/fd/1" to "/dev/stdout",
            "/proc/self/fd/2" to "/dev/stderr",
            File(rootfs, "tmp").path to "/dev/shm",
            paths.bridgeDir.path to GUEST_BRIDGE_DIR,
            paths.driveDir.path to GUEST_DRIVE_DIR,
        )
        list += options.fakeProc
        if (options.bindStorage) {
            list += STORAGE_DIR to GUEST_STORAGE_DIR
            list += STORAGE_DIR to "/sdcard"
        }
        if (paths.pocketDevDir.isDirectory) list += paths.pocketDevDir.path to GUEST_POCKET_DEV_DIR
        if (paths.rclone.isFile) list += paths.rclone.path to "/usr/local/bin/rclone"
        return list
    }

    private fun guestEnv(config: ContainerConfig, home: String, user: String): List<String> = listOf(
        "HOME=$home",
        "USER=$user",
        "LOGNAME=$user",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
        "PATH=$GUEST_PATH",
        "BROWSER=$GUEST_OPEN_COMMAND",
        "POCKET_LINUX=1",
        "POCKET_LINUX_CONTAINER=${config.name}",
    )

    private fun hostEnv(rootfs: File): List<String> = buildList {
        add("PROOT_LOADER=${paths.prootLoader.path}")
        add("PROOT_TMP_DIR=${paths.prootTmpDir.path}")
        // ハードリンクの代わりに作る隠しファイルは、作業フォルダ（Drive と同期する場所など）ではなく rootfs に置く
        add("PROOT_L2S_DIR=${File(rootfs, L2sLinks.DIR).path}")
        add("PATH=/system/bin")
        add("HOME=${paths.filesDir.path}")
        System.getenv("ANDROID_ROOT")?.let { add("ANDROID_ROOT=$it") }
        System.getenv("ANDROID_DATA")?.let { add("ANDROID_DATA=$it") }
    }

    companion object {
        const val GUEST_BRIDGE_DIR = "/run/pocket-linux"
        const val GUEST_DRIVE_DIR = "/mnt/drive"
        const val GUEST_STORAGE_DIR = "/mnt/storage"
        const val GUEST_POCKET_DEV_DIR = "/opt/pocket-dev"
        const val GUEST_OPEN_COMMAND = "/usr/local/bin/pocket-open"
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        const val STORAGE_DIR = "/storage/emulated/0"
        private val SU_PATHS = listOf("bin/su", "usr/bin/su", "sbin/su")

        /** ログインに使うシェル（bash が無いイメージでは sh） */
        fun loginShell(rootfs: File): String =
            if (File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()) "/bin/bash" else "/bin/sh"

        /** 単体テストできるよう、組み立て部分だけを切り出している */
        fun buildArgs(
            proot: String,
            rootfs: String,
            workDir: String,
            binds: List<Pair<String, String>>,
            guestEnv: List<String>,
            command: List<String>,
        ): List<String> = buildList {
            add(proot)
            add("--kill-on-exit")
            add("--link2symlink")
            add("--sysvipc")
            add("-0")
            add("-r"); add(rootfs)
            for ((host, guest) in binds) {
                add("-b"); add(if (host == guest) host else "$host:$guest")
            }
            add("-w"); add(workDir)
            add("/usr/bin/env"); add("-i")
            addAll(guestEnv)
            addAll(command)
        }
    }
}

/** Android 以外（単体テスト）でも ProotLauncher を使えるよう、lstat の失敗を握りつぶす */
private object AndroidFileOpsSafe {
    fun isSymlink(file: File): Boolean = runCatching { AndroidFileOps.isSymlink(file) }.getOrDefault(false)
}
