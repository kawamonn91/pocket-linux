package com.ttech.pocketlinux.vm

/** VM の起動に使う場所と大きさ */
data class VmConfig(
    val qemu: String,
    val kernel: String,
    val system: String,
    val data: String,
    val agentSocket: String,
    val qmpSocket: String,
    val consoleLog: String,
    val pidFile: String,
    val memoryMb: Int,
    val cpus: Int,
    /** 端末のストレージを 9p で見せるときのパス（許可が無ければ null） */
    val storage: String? = null,
)

/** QEMU のコマンドライン。docs/vm-plan.md の 4.3、CI の vm/test/boot_test.py と同じ形 */
object QemuLauncher {
    /** `ip=` でネットワークを決める（DHCP を待たない）。panic で止まり、-no-reboot で QEMU が終わる */
    const val KERNEL_ARGS = "console=hvc0 root=/dev/vda rootfstype=erofs ro init=/sbin/pocket-agent quiet " +
        "mitigations=off panic=-1 ip=10.0.2.15::10.0.2.2:255.255.255.0::eth0:off"

    /** ゲストの中で見えるアドレス（ポート転送の宛先） */
    const val GUEST_ADDRESS = "10.0.2.15"
    const val NET_ID = "n0"

    fun argv(c: VmConfig): List<String> = buildList {
        add(c.qemu)
        addAll(listOf("-M", "virt", "-cpu", "max,pauth-impdef=on"))
        addAll(listOf("-smp", c.cpus.toString(), "-m", c.memoryMb.toString()))
        addAll(listOf("-accel", "tcg,thread=multi,tb-size=256"))
        addAll(listOf("-nodefaults", "-no-user-config", "-nographic", "-no-reboot"))
        addAll(listOf("-pidfile", c.pidFile))
        addAll(listOf("-kernel", c.kernel, "-append", KERNEL_ARGS))
        // virt マシンは後ろの -device を先に見つける。system を /dev/vda にするため後ろに書く
        addAll(listOf("-drive", "if=none,id=data,file=${c.data},format=raw,cache=writeback,discard=unmap,aio=threads"))
        addAll(listOf("-device", "virtio-blk-device,drive=data,serial=pocket-data"))
        addAll(listOf("-drive", "if=none,id=sys,file=${c.system},format=raw,readonly=on"))
        addAll(listOf("-device", "virtio-blk-device,drive=sys,serial=pocket-system"))
        addAll(listOf("-netdev", "user,id=$NET_ID", "-device", "virtio-net-device,netdev=$NET_ID"))
        add("-device"); add("virtio-serial-device")
        addAll(listOf("-chardev", "socket,id=agent,path=${c.agentSocket},server=on,wait=off"))
        addAll(listOf("-device", "virtserialport,chardev=agent,name=pocket.agent"))
        addAll(listOf("-chardev", "file,id=con,path=${c.consoleLog}", "-device", "virtconsole,chardev=con"))
        addAll(listOf("-device", "virtio-rng-device"))
        addAll(listOf("-device", "virtio-balloon-device,free-page-reporting=on"))
        c.storage?.let {
            addAll(listOf("-fsdev", "local,id=storage,path=$it,security_model=none"))
            addAll(listOf("-device", "virtio-9p-device,fsdev=storage,mount_tag=storage"))
        }
        addAll(listOf("-qmp", "unix:${c.qmpSocket},server=on,wait=off"))
    }

    /** 端末の RAM に合わせた既定のメモリ（MB）。使った分だけ確保され、空きは balloon で Android に返す */
    fun defaultMemoryMb(totalRamBytes: Long): Int = if (totalRamBytes < 8L * GIB - GIB / 2) 512 else 1024

    /** vCPU の数の既定。増やしても TCG は重くなるだけなので 4 までにする */
    fun defaultCpus(hostCpus: Int): Int = hostCpus.coerceIn(1, 4)

    private const val GIB = 1L shl 30
}
