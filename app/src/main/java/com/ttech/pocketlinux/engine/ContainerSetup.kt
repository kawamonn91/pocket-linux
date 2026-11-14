package com.ttech.pocketlinux.engine

import com.ttech.pocketlinux.vm.Guest
import com.ttech.pocketlinux.vm.GuestCommands
import java.util.concurrent.TimeUnit

/** コンテナの初期設定で、sudo のほかに入れるもの */
data class SetupOptions(
    /** よく使うツール（curl・git・nano・less など） */
    val tools: Boolean = false,
    /** 日本語環境（ja_JP.UTF-8 の表示と日本時間） */
    val japanese: Boolean = false,
    /** C・Java・Python の開発環境（gcc・make・JDK・pip・venv） */
    val devTools: Boolean = false,
) {
    /** 進み具合に出す「〜を入れています」の〜の部分 */
    fun label(): String = buildList {
        add("sudo")
        if (tools) add("よく使うツール")
        if (japanese) add("日本語環境")
        if (devTools) add("C・Java・Python")
    }.joinToString("、")

    companion object {
        /** 初期設定のやり直し・復元のとき（sudo だけ） */
        val SUDO_ONLY = SetupOptions()

        /** 作成画面の初めの選択 */
        val DEFAULT = SetupOptions(tools = true, japanese = true, devTools = true)
    }
}

/** パッケージを入れた結果。[skipped] は入れられなかった任意のもの（日本語環境・C・Java・Python） */
data class InstallResult(val exit: Int, val skipped: List<String>)

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

    /**
     * sudo と、[options] で選んだものを入れる。日本語環境・C・Java・Python は入れられなくても失敗にせず、
     * [InstallResult.skipped] で知らせる。[onStep] には、いま何を入れているか（スクリプトの「== 」の行）が届く。
     */
    suspend fun installPackages(
        config: ContainerConfig,
        options: SetupOptions,
        log: (String) -> Unit,
        onStep: (String) -> Unit = {},
    ): InstallResult {
        val skipped = mutableListOf<String>()
        val exit = guest.run(GuestCommands.rootScript(config, packageInstallScript(options))) { line ->
            parseSkipped(line)?.let { skipped += it }
            line.removePrefix(STEP_PREFIX).takeIf { it != line }?.let(onStep)
            log(line)
        }
        return InstallResult(exit, skipped)
    }

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
        if (output.exit != 0) throw ContainerException("ホームフォルダを作れませんでした: ${output.failureReason()}")
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
        if (output.exit != 0) throw ContainerException("移行した中身を直せませんでした: ${output.failureReason()}")
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

        /** スクリプトの、いま何をしているかを知らせる行の頭 */
        private const val STEP_PREFIX = "== "

        /** スクリプトの、入れられなかったものを知らせる行の頭（install-packages.sh の skip と合わせる） */
        private const val SKIPPED_PREFIX = "!! 入れられませんでした: "

        /** 本体は resources の install-packages.sh（R8 がクラスの場所を変えても読めるよう、絶対パスで読む） */
        private val INSTALL_SCRIPT: String by lazy {
            val stream = ContainerSetup::class.java.getResourceAsStream("/pocketlinux/install-packages.sh")
                ?: error("install-packages.sh が APK に入っていません")
            stream.use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")
        }

        /** sudo と、[options] で選んだものを入れるスクリプト。root で実行する */
        fun packageInstallScript(options: SetupOptions): String = buildString {
            fun flag(on: Boolean) = if (on) "1" else "0"
            appendLine("POCKET_TOOLS=${flag(options.tools)}")
            appendLine("POCKET_JAPANESE=${flag(options.japanese)}")
            appendLine("POCKET_DEV=${flag(options.devTools)}")
            append(INSTALL_SCRIPT)
        }

        fun parseSkipped(line: String): String? =
            line.takeIf { it.startsWith(SKIPPED_PREFIX) }?.removePrefix(SKIPPED_PREFIX)?.trim()?.ifEmpty { null }

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
