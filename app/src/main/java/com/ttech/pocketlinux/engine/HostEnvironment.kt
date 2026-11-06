package com.ttech.pocketlinux.engine

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import com.ttech.pocketlinux.vm.GuestCommands
import java.net.Inet6Address
import java.net.NetworkInterface

/** VM の起動に必要な、端末側の情報 */
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

    /** VM に教える DNS サーバー */
    fun guestDnsServers(): List<String> = GuestCommands.dnsForGuest(dnsServers())

    /** ネットワークやその DNS が変わったら呼ぶ（VM の DNS を書き直すため） */
    fun onNetworkChanged(listener: () -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = listener()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = listener()
            })
        }
    }

    /** 端末の RAM（VM のメモリの既定を決める） */
    fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    /** インターネットにつながっているか（Drive の定期的な取り込みを、つながっていないときは休む） */
    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** 「すべてのファイルへのアクセス」が許可されているか */
    fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

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
