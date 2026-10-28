package com.ttech.pocketlinux.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * コンテナの中に、Pocket Linux で動かすためのファイルを書き込む。
 * どのディストリビューションでも動くよう、コマンドではなくファイル操作で行う。
 */
class RootfsSetup(private val ops: FileOps) {

    /** パッケージを入れる前に必要なもの（名前解決・ブラウザ連携・環境変数） */
    fun prepareBase(rootfs: File, config: ContainerConfig, dnsServers: List<String>) {
        File(rootfs, "tmp").mkdirs()
        runCatching { ops.chmod(File(rootfs, "tmp"), "1777".toInt(8)) }
        writeResolvConf(rootfs, dnsServers)
        writeHosts(rootfs)
        writeEtcFile(rootfs, "etc/hostname", "$HOSTNAME\n")

        writeEtcFile(rootfs, "etc/profile.d/pocket-linux.sh", PROFILE_SCRIPT)
        val imageEnv = config.env
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .filter { (key, _) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && key !in IGNORED_IMAGE_ENV }
            .joinToString("") { (key, value) -> "export $key=${shellQuote(value)}\n" }
        writeEtcFile(rootfs, "etc/profile.d/00-image-env.sh", "# イメージの ENV（Pocket Linux が作成）\n$imageEnv")

        for (name in listOf("pocket-open", "xdg-open")) {
            val file = File(rootfs, "usr/local/bin/$name")
            writeEtcFile(rootfs, "usr/local/bin/$name", OPEN_SCRIPT)
            ops.chmod(file, "755".toInt(8))
        }
    }

    /** パッケージを入れた後に、一般ユーザーと sudo の設定を作る */
    fun createUser(rootfs: File, config: ContainerConfig, androidGids: List<Int>, nowMillis: Long = System.currentTimeMillis()) {
        val user = config.user ?: return
        val shell = ProotLauncher.loginShell(rootfs)
        val passwd = File(rootfs, "etc/passwd")
        val group = File(rootfs, "etc/group")
        val shadow = File(rootfs, "etc/shadow")
        val result = Accounts.addUser(
            passwd = passwd.takeIf { it.isFile }?.readText().orEmpty(),
            group = group.takeIf { it.isFile }?.readText().orEmpty(),
            shadow = shadow.takeIf { it.isFile }?.readText(),
            user = user,
            home = config.home,
            shell = shell,
            androidGids = androidGids,
            daysSinceEpoch = TimeUnit.MILLISECONDS.toDays(nowMillis),
        )
        writeEtcFile(rootfs, "etc/passwd", result.passwd)
        writeEtcFile(rootfs, "etc/group", result.group)
        result.shadow?.let {
            writeEtcFile(rootfs, "etc/shadow", it)
            runCatching { ops.chmod(shadow, "640".toInt(8)) }
        }

        // ホームフォルダと、/etc/skel の初期ファイル（.bashrc など）
        val home = RootfsPath.resolve(rootfs, config.home, ops, followLast = true)
        home.mkdirs()
        File(rootfs, "etc/skel").listFiles()?.forEach { skel ->
            val dest = File(home, skel.name)
            if (skel.isFile && !dest.exists()) skel.copyTo(dest)
        }
        linkInHome(home, "drive", ProotLauncher.GUEST_DRIVE_DIR)
        linkInHome(home, "storage", ProotLauncher.GUEST_STORAGE_DIR)
        linkInHome(File(rootfs, "root").apply { mkdirs() }, "drive", ProotLauncher.GUEST_DRIVE_DIR)

        // パスワードなしの sudo（sudoers.d を読まない設定のイメージには読む行を足す）
        writeEtcFile(rootfs, "etc/sudoers.d/pocket-linux", "$user ALL=(ALL:ALL) NOPASSWD: ALL\n")
        runCatching { ops.chmod(File(rootfs, "etc/sudoers.d/pocket-linux"), "440".toInt(8)) }
        val sudoers = File(rootfs, "etc/sudoers")
        if (sudoers.isFile) {
            val text = sudoers.readText()
            if (!Regex("^[@#]includedir\\s+/etc/sudoers.d", RegexOption.MULTILINE).containsMatchIn(text)) {
                sudoers.appendText("\n@includedir /etc/sudoers.d\n")
            }
        }
    }

    /** 名前解決の設定。端末の DNS は接続先で変わるので、コンテナを開くたびに書き直す */
    fun writeResolvConf(rootfs: File, dnsServers: List<String>) {
        val servers = dnsServers.filter { it.isNotBlank() }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        writeEtcFile(rootfs, "etc/resolv.conf", servers.joinToString("") { "nameserver $it\n" })
    }

    private fun writeHosts(rootfs: File) {
        val hosts = File(rootfs, "etc/hosts")
        val current = if (hosts.isFile && !ops.isSymlink(hosts)) hosts.readText() else ""
        if (current.lines().any { it.trim().startsWith("127.0.0.1") && it.contains("localhost") }) return
        writeEtcFile(rootfs, "etc/hosts", "127.0.0.1 localhost $HOSTNAME\n::1 localhost ip6-localhost ip6-loopback\n$current")
    }

    private fun linkInHome(home: File, name: String, target: String) {
        val link = File(home, name)
        if (ops.existsNoFollow(link)) return
        runCatching { ops.symlink(target, link) }
    }

    /**
     * rootfs 内のファイルを書く。リンクになっている場合（resolv.conf → systemd のものなど）は
     * リンクをたどらずに置き換える。
     */
    private fun writeEtcFile(rootfs: File, path: String, content: String) {
        val parent = RootfsPath.resolve(rootfs, path.substringBeforeLast('/'), ops, followLast = true)
        parent.mkdirs()
        val file = File(parent, path.substringAfterLast('/'))
        if (ops.isSymlink(file) || file.isDirectory) ops.deleteTree(file)
        file.writeText(content)
    }

    companion object {
        const val HOSTNAME = "pocket-linux"

        private val IGNORED_IMAGE_ENV = setOf("HOME", "USER", "LOGNAME", "HOSTNAME", "TERM", "SHELL", "PWD")

        private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        /** ログインシェルで読み込まれる（su -l で環境変数が消えるため、ここで設定し直す） */
        val PROFILE_SCRIPT = """
            |# Pocket Linux が作成（コンテナを作るときに書き込まれる）
            |export LANG="${'$'}{LANG:-C.UTF-8}"
            |export COLORTERM=truecolor
            |export BROWSER=${ProotLauncher.GUEST_OPEN_COMMAND}
            |export POCKET_LINUX=1
            |case ":${'$'}PATH:" in
            |  *":${'$'}HOME/.local/bin:"*) ;;
            |  *) export PATH="${'$'}HOME/.local/bin:${'$'}PATH" ;;
            |esac
            |""".trimMargin()

        /** コンテナ内から Android のブラウザで URL を開く（Claude Code・agy のログインなどで使われる） */
        val OPEN_SCRIPT = """
            |#!/bin/sh
            |# URL を Android のブラウザで開く（Pocket Linux）
            |if [ -z "${'$'}1" ]; then
            |  echo "使い方: ${'$'}(basename "${'$'}0") URL" >&2
            |  exit 1
            |fi
            |dir=${ProotLauncher.GUEST_BRIDGE_DIR}/open
            |if [ ! -d "${'$'}dir" ]; then
            |  echo "Pocket Linux の外では使えません" >&2
            |  exit 1
            |fi
            |tmp="${'$'}dir/.${'$'}${'$'}.tmp"
            |printf '%s\n' "${'$'}1" > "${'$'}tmp" && mv "${'$'}tmp" "${'$'}dir/${'$'}${'$'}-${'$'}(date +%s).url"
            |echo "ブラウザで開きました: ${'$'}1"
            |""".trimMargin()

        /**
         * sudo（と、選んだ場合はよく使うツール）を入れるスクリプト。proot の root として実行する。
         */
        fun packageInstallScript(installTools: Boolean): String {
            val apt = if (installTools) "ca-certificates curl git nano less procps" else ""
            val apk = if (installTools) "bash ca-certificates curl git nano less procps" else ""
            val dnf = if (installTools) "ca-certificates curl git nano less procps-ng" else ""
            return """
                |set -e
                |export DEBIAN_FRONTEND=noninteractive
                |if command -v apt-get >/dev/null 2>&1; then
                |  echo "== パッケージの一覧を更新しています"
                |  apt-get update
                |  echo "== sudo などを入れています"
                |  apt-get install -y --no-install-recommends sudo $apt
                |elif command -v apk >/dev/null 2>&1; then
                |  echo "== sudo などを入れています"
                |  apk add --no-cache sudo $apk
                |elif command -v dnf >/dev/null 2>&1; then
                |  dnf install -y sudo $dnf
                |elif command -v microdnf >/dev/null 2>&1; then
                |  microdnf install -y sudo $dnf
                |elif command -v zypper >/dev/null 2>&1; then
                |  zypper --non-interactive install sudo $apt
                |elif command -v pacman >/dev/null 2>&1; then
                |  pacman -Sy --noconfirm sudo $apt
                |else
                |  echo "パッケージマネージャーが見つからないため、sudo を入れられませんでした" >&2
                |  exit 3
                |fi
                |chmod 4755 "${'$'}(command -v sudo)"
                |echo "== 完了"
                |""".trimMargin()
        }
    }
}
