package com.ttech.pocketlinux.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface

/** コンテナの起動に必要な、端末側の情報 */
class HostEnvironment(private val context: Context) {

    /** 今つながっているネットワークの DNS サーバー（取れなければ空） */
    fun dnsServers(): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        props.dnsServers
            // リンクローカルの IPv6（fe80::…%wlan0）は resolv.conf に書けないので除く
            .filterNot { it is Inet6Address && it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())

    /** インターネットにつながっているか（Drive の定期的な取り込みを、つながっていないときは休む） */
    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** アプリのプロセスが属するグループ（/proc/self/status の Groups 行） */
    fun groupIds(): List<Int> = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull() }
            .orEmpty()
    }.getOrDefault(emptyList())

    /** 「すべてのファイルへのアクセス」が許可されているか */
    fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun platform(): Platform = Platform.fromAbi(Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "")

    /** LAN・Tailscale から IDE で接続するときの、この端末の IP アドレス（表示用） */
    fun localAddresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filter { it !is Inet6Address }
                    .mapNotNull { address -> address.hostAddress?.let { nif.name to it } }
            }
    }.getOrDefault(emptyList())
}
