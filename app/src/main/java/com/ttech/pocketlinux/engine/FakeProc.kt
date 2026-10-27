package com.ttech.pocketlinux.engine

import java.io.File

/**
 * Android はアプリに /proc/stat などを読ませないため、代わりのファイルを用意してコンテナに見せる。
 * Node.js の os.cpus() や top などが、読めないと失敗するため（proot-distro と同じ対策）。
 */
object FakeProc {

    /** コンテナ内のパス → 中身 */
    fun files(cpuCount: Int): Map<String, String> = mapOf(
        "/proc/loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "/proc/stat" to stat(cpuCount),
        "/proc/uptime" to "124.08 932.80\n",
        "/proc/version" to "Linux version 6.6.0-pocket-linux (pocket-linux@localhost) (gcc) #1 SMP PREEMPT\n",
        "/proc/vmstat" to VMSTAT,
        "/proc/sys/kernel/cap_last_cap" to "40\n",
        "/proc/sys/fs/inotify/max_user_watches" to "4096\n",
        "/proc/sys/crypto/fips_enabled" to "0\n",
    )

    /**
     * 本物が読めないものだけ、代わりのファイルを書いて (ホスト側のパス, コンテナ内のパス) を返す。
     */
    fun prepare(dir: File, cpuCount: Int = Runtime.getRuntime().availableProcessors()): List<Pair<String, String>> {
        dir.mkdirs()
        return files(cpuCount).mapNotNull { (guestPath, content) ->
            if (isReadable(File(guestPath))) return@mapNotNull null
            val file = File(dir, guestPath.removePrefix("/").replace('/', '_'))
            if (!file.isFile || file.readText() != content) file.writeText(content)
            file.path to guestPath
        }
    }

    private fun isReadable(file: File): Boolean = try {
        file.inputStream().use { it.read() }
        true
    } catch (_: Exception) {
        false
    }

    private fun stat(cpuCount: Int): String = buildString {
        append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
        repeat(cpuCount.coerceAtLeast(1)) { i -> append("cpu$i 31 0 226 12027 82 10 4 9 0 0\n") }
        append("intr 63361 0 20 0 0 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n")
        append("ctxt 38014093\n")
        append("btime 1694292441\n")
        append("processes 26442\n")
        append("procs_running 1\n")
        append("procs_blocked 0\n")
        append("softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
    }

    private const val VMSTAT = """nr_free_pages 1743136
nr_zone_inactive_anon 179281
nr_zone_active_anon 7183
nr_zone_inactive_file 22858
nr_zone_active_file 51328
nr_zone_unevictable 642
nr_zone_write_pending 0
nr_mlock 0
nr_bounce 0
nr_zspages 0
nr_free_cma 0
numa_hit 1259626
numa_miss 0
numa_foreign 0
numa_interleave 720
numa_local 1259626
numa_other 0
nr_inactive_anon 179281
nr_active_anon 7183
nr_inactive_file 22858
nr_active_file 51328
nr_unevictable 642
nr_slab_reclaimable 8091
nr_slab_unreclaimable 7804
nr_isolated_anon 0
nr_isolated_file 0
nr_dirty 0
nr_writeback 0
nr_file_pages 254032
nr_mapped 41040
nr_shmem 179918
pgpgin 198464
pgpgout 6772
pswpin 0
pswpout 0
pgalloc_dma 0
pgalloc_normal 1325856
pgfree 3107386
pgactivate 0
pgdeactivate 0
pgfault 1279012
pgmajfault 1072
"""
}
