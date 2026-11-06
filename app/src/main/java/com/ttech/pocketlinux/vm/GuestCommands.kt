package com.ttech.pocketlinux.vm

import com.ttech.pocketlinux.engine.ContainerConfig

/** コンテナの中で動かすコマンド（OPEN の中身）を組み立てる */
object GuestCommands {
    const val HOSTNAME = "pocket-linux"
    const val DRIVE_DIR = "/mnt/drive"
    const val STORAGE_DIR = "/mnt/storage"
    const val POCKET_DEV_DIR = "/opt/pocket-dev"
    const val BRIDGE_DIR = "/run/pocket-linux"
    const val OPEN_COMMAND = "/usr/local/bin/pocket-open"
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** 一般ユーザーへは su で切り替える（$1 がユーザー。無ければ、あるいは su が無ければ root のシェル） */
    private const val LOGIN_SCRIPT = """
if [ -n "${'$'}1" ] && command -v su >/dev/null 2>&1; then exec su -l "${'$'}1"; fi
if [ -x /bin/bash ]; then exec /bin/bash -l; fi
exec /bin/sh -l
"""

    /** $1 がユーザー、$2 が実行するスクリプト */
    private const val EXEC_SCRIPT = """
if [ -n "${'$'}1" ] && command -v su >/dev/null 2>&1; then exec su -l "${'$'}1" -c "${'$'}2"; fi
if [ -x /bin/bash ]; then exec /bin/bash -lc "${'$'}2"; fi
exec /bin/sh -lc "${'$'}2"
"""

    /** ターミナルのログインシェル */
    fun login(config: ContainerConfig, asRoot: Boolean): OpenRequest {
        val user = effectiveUser(config, asRoot)
        return OpenRequest(
            op = "exec",
            container = config.id,
            hostname = HOSTNAME,
            argv = listOf("/bin/sh", "-c", LOGIN_SCRIPT, "sh", user.orEmpty()),
            env = env(config, user),
            cwd = home(config, user),
        )
    }

    /** スクリプトを 1 つ動かす（自動実行・IDE のサーバーなど） */
    fun exec(config: ContainerConfig, script: String, asRoot: Boolean): OpenRequest {
        val user = effectiveUser(config, asRoot)
        return OpenRequest(
            op = "exec",
            container = config.id,
            hostname = HOSTNAME,
            argv = listOf("/bin/sh", "-c", EXEC_SCRIPT, "sh", user.orEmpty(), script),
            env = env(config, user),
            cwd = home(config, user),
        )
    }

    /** 初期設定用: root で、環境をログインに頼らずに動かす（パッケージを入れる前は /etc/profile も不完全なことがある） */
    fun rootScript(config: ContainerConfig, script: String): OpenRequest = OpenRequest(
        op = "exec",
        container = config.id,
        hostname = HOSTNAME,
        argv = listOf("/bin/sh", "-c", script),
        env = env(config, null) + "DEBIAN_FRONTEND=noninteractive",
        cwd = "/",
    )

    private fun effectiveUser(config: ContainerConfig, asRoot: Boolean): String? = if (asRoot) null else config.user

    private fun home(config: ContainerConfig, user: String?): String = if (user == null) "/root" else config.home

    fun env(config: ContainerConfig, user: String?): List<String> = listOf(
        "HOME=${home(config, user)}",
        "USER=${user ?: "root"}",
        "LOGNAME=${user ?: "root"}",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
        "PATH=$PATH",
        "BROWSER=$OPEN_COMMAND",
        "POCKET_LINUX=1",
        "POCKET_LINUX_CONTAINER=${config.name}",
    )

    /**
     * ゲストに教える DNS サーバー。IPv4 だけにする（slirp の外への通信は IPv4 で出す）。
     * 10.0.2.x は slirp 自身のアドレスなので、エミュレーターの DNS（10.0.2.3）が来ても使えない。
     */
    fun dnsForGuest(servers: List<String>): List<String> =
        servers.filter { IPV4.matches(it) && !it.startsWith("10.0.2.") }.ifEmpty { FALLBACK_DNS }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")
}
