package com.ttech.pocketlinux.engine

import com.ttech.pocketlinux.vm.Guest
import com.ttech.pocketlinux.vm.GuestCommands
import java.util.concurrent.TimeUnit

/**
 * コンテナの中に、Pocket Linux で動かすためのファイルと一般ユーザーを作る。
 * パスワードなどのファイルは useradd が無いイメージ（Alpine など）でも動くよう直接書き換え、
 * ホームフォルダはシェルで作る（所有者を本物にするため）。
 */
class ContainerSetup(private val guest: Guest) {

    /** パッケージを入れる前に必要なもの（ブラウザ連携・環境変数）。名前解決はコンテナの起動時にエージェントが書く */
    suspend fun prepareBase(config: ContainerConfig) {
        val id = config.id
        val hosts = guest.readFile(id, "/etc/hosts").orEmpty()
        if (hosts.lines().none { it.trim().startsWith("127.0.0.1") && it.contains("localhost") }) {
            guest.writeFile(id, "/etc/hosts", "127.0.0.1 localhost ${GuestCommands.HOSTNAME}\n::1 localhost ip6-localhost ip6-loopback\n$hosts")
        }
        guest.writeFile(id, "/etc/hostname", "${GuestCommands.HOSTNAME}\n")
        guest.writeFile(id, "/etc/profile.d/pocket-linux.sh", PROFILE_SCRIPT)
        guest.writeFile(id, "/etc/profile.d/00-image-env.sh", imageEnvScript(config.env))
        for (name in listOf("pocket-open", "xdg-open")) {
            guest.writeFile(id, "/usr/local/bin/$name", OPEN_SCRIPT, mode = "755".toInt(8))
        }
    }

    /** sudo などを入れる。終了コードを返す */
    suspend fun installPackages(config: ContainerConfig, installTools: Boolean, log: (String) -> Unit): Int =
        guest.run(GuestCommands.rootScript(config, packageInstallScript(installTools)), log)

    /** パッケージを入れた後に、一般ユーザーと sudo の設定を作る */
    suspend fun createUser(config: ContainerConfig, nowMillis: Long = System.currentTimeMillis()) {
        val user = config.user ?: return
        val id = config.id
        val shell = if (guest.capture(GuestCommands.rootScript(config, "test -x /bin/bash")).exit == 0) "/bin/bash" else "/bin/sh"
        val result = Accounts.addUser(
            passwd = guest.readFile(id, "/etc/passwd").orEmpty(),
            group = guest.readFile(id, "/etc/group").orEmpty(),
            shadow = guest.readFile(id, "/etc/shadow"),
            user = user,
            home = config.home,
            shell = shell,
            daysSinceEpoch = TimeUnit.MILLISECONDS.toDays(nowMillis),
        )
        guest.writeFile(id, "/etc/passwd", result.passwd)
        guest.writeFile(id, "/etc/group", result.group)
        result.shadow?.let { guest.writeFile(id, "/etc/shadow", it, mode = "640".toInt(8)) }

        // パスワードなしの sudo（sudoers.d を読まない設定のイメージには読む行を足す）
        guest.writeFile(id, "/etc/sudoers.d/pocket-linux", "$user ALL=(ALL:ALL) NOPASSWD: ALL\n", mode = "440".toInt(8))
        guest.readFile(id, "/etc/sudoers")?.let { text ->
            if (!Regex("^[@#]includedir\\s+/etc/sudoers.d", RegexOption.MULTILINE).containsMatchIn(text)) {
                guest.writeFile(id, "/etc/sudoers", "$text\n@includedir /etc/sudoers.d\n", mode = "440".toInt(8))
            }
        }

        val output = guest.capture(GuestCommands.rootScript(config, homeScript(config.home, result.uid)))
        if (output.exit != 0) throw ContainerException("ホームフォルダを作れませんでした: ${output.stderr.trim().lines().lastOrNull().orEmpty()}")
    }

    /**
     * 1.x（proot）で作った中身を VM で使えるように直す。proot は所有者を記録しないので、すべて root の持ち物になっている。
     * ハードリンクの代わりのリンク（.l2s）はリンクのまま動くので残す。
     */
    suspend fun repairLegacy(config: ContainerConfig) {
        val uid = config.user?.let { user ->
            guest.readFile(config.id, "/etc/passwd")?.lineSequence()
                ?.firstOrNull { it.substringBefore(':') == user }?.split(':')?.getOrNull(2)?.toIntOrNull()
        }
        val output = guest.capture(GuestCommands.rootScript(config, legacyRepairScript(config.home, uid)))
        if (output.exit != 0) throw ContainerException("移行した中身を直せませんでした: ${output.stderr.trim().lines().lastOrNull().orEmpty()}")
    }

    companion object {
        private val IGNORED_IMAGE_ENV = setOf("HOME", "USER", "LOGNAME", "HOSTNAME", "TERM", "SHELL", "PWD")

        private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        fun imageEnvScript(env: List<String>): String {
            val exports = env
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .filter { (key, _) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && key !in IGNORED_IMAGE_ENV }
                .joinToString("") { (key, value) -> "export $key=${shellQuote(value)}\n" }
            return "# イメージの ENV（Pocket Linux が作成）\n$exports"
        }

        /** ログインシェルで読み込まれる（su -l で環境変数が消えるため、ここで設定し直す） */
        val PROFILE_SCRIPT = """
            |# Pocket Linux が作成（コンテナを作るときに書き込まれる）
            |export LANG="${'$'}{LANG:-C.UTF-8}"
            |export COLORTERM=truecolor
            |export BROWSER=${GuestCommands.OPEN_COMMAND}
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
            |dir=${GuestCommands.BRIDGE_DIR}/open
            |if [ ! -d "${'$'}dir" ]; then
            |  echo "Pocket Linux の外では使えません" >&2
            |  exit 1
            |fi
            |tmp="${'$'}dir/.${'$'}${'$'}.tmp"
            |printf '%s\n' "${'$'}1" > "${'$'}tmp" && mv "${'$'}tmp" "${'$'}dir/${'$'}${'$'}-${'$'}(date +%s).url"
            |echo "ブラウザで開きました: ${'$'}1"
            |""".trimMargin()

        /** sudo（と、選んだ場合はよく使うツール）を入れるスクリプト。root で実行する */
        fun packageInstallScript(installTools: Boolean): String {
            val apt = if (installTools) "ca-certificates curl git nano less procps" else ""
            val apk = if (installTools) "bash ca-certificates curl git nano less procps" else ""
            val dnf = if (installTools) "ca-certificates curl git nano less procps-ng" else ""
            return """
                |set -e
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
                |command -v sudo >/dev/null
                |echo "== 完了"
                |""".trimMargin()
        }

        /** ホームフォルダを作り、/etc/skel の初期ファイルとリンクを置いて、持ち主を一般ユーザーにする */
        fun homeScript(home: String, uid: Int): String = """
            |set -e
            |home=${shellQuote(home)}
            |mkdir -p "${'$'}home" /root
            |for f in /etc/skel/.[!.]* /etc/skel/*; do
            |  [ -e "${'$'}f" ] || continue
            |  [ -e "${'$'}home/${'$'}{f##*/}" ] || cp -a "${'$'}f" "${'$'}home/"
            |done
            |[ -e "${'$'}home/drive" ] || ln -s ${GuestCommands.DRIVE_DIR} "${'$'}home/drive"
            |[ -e "${'$'}home/storage" ] || ln -s ${GuestCommands.STORAGE_DIR} "${'$'}home/storage"
            |[ -e /root/drive ] || ln -s ${GuestCommands.DRIVE_DIR} /root/drive
            |chown -Rh $uid:$uid "${'$'}home"
            |""".trimMargin()

        fun legacyRepairScript(home: String, uid: Int?): String = buildString {
            appendLine("chmod 1777 /tmp 2>/dev/null")
            if (uid != null) appendLine("chown -Rh $uid:$uid ${shellQuote(home)} 2>/dev/null")
            // 古いバックアップの、端末側の絶対パスを指すリンク（/data/.../containers/<id>/rootfs/.l2s/...）を直す
            appendLine(
                """find / -xdev -type l -lname '/*/containers/*/rootfs/*' 2>/dev/null | while IFS= read -r l; do t=${'$'}(readlink "${'$'}l"); ln -sfn "/${'$'}{t#*/rootfs/}" "${'$'}l"; done""",
            )
            appendLine("exit 0")
        }
    }
}
