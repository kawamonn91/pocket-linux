package com.ttech.pocketlinux.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QemuLauncherTest {
    private val config = VmConfig(
        qemu = "/lib/libqemu.so",
        kernel = "/vm/Image",
        system = "/vm/system.erofs",
        data = "/vm/data.img",
        agentSocket = "/vm/agent.sock",
        qmpSocket = "/vm/qmp.sock",
        consoleLog = "/vm/console.log",
        pidFile = "/vm/qemu.pid",
        memoryMb = 1024,
        cpus = 4,
    )

    @Test
    fun systemのディスクはdataより後ろに書く() {
        // virt マシンは後ろの -device を先に見つける。system を /dev/vda にするため
        val argv = QemuLauncher.argv(config)
        val data = argv.indexOf("virtio-blk-device,drive=data,serial=pocket-data")
        val system = argv.indexOf("virtio-blk-device,drive=sys,serial=pocket-system")
        assertTrue(data in 0 until system)
        assertEquals("/lib/libqemu.so", argv.first())
    }

    @Test
    fun エージェントとQMPのソケットを開く() {
        val argv = QemuLauncher.argv(config)
        assertTrue("socket,id=agent,path=/vm/agent.sock,server=on,wait=off" in argv)
        assertTrue("virtserialport,chardev=agent,name=pocket.agent" in argv)
        assertTrue("unix:/vm/qmp.sock,server=on,wait=off" in argv)
    }

    @Test
    fun 端末のストレージは許可があるときだけ見せる() {
        assertFalse(QemuLauncher.argv(config).any { it.contains("9p") })
        val argv = QemuLauncher.argv(config.copy(storage = "/storage/emulated/0"))
        assertTrue("local,id=storage,path=/storage/emulated/0,security_model=none" in argv)
        assertTrue("virtio-9p-device,fsdev=storage,mount_tag=storage" in argv)
    }

    @Test
    fun 既定のメモリとCPU() {
        assertEquals(512, QemuLauncher.defaultMemoryMb(6L shl 30))
        assertEquals(1024, QemuLauncher.defaultMemoryMb(12L shl 30))
        assertEquals(4, QemuLauncher.defaultCpus(8))
        assertEquals(2, QemuLauncher.defaultCpus(2))
    }

    @Test
    fun ポート転送のコマンド() {
        assertEquals("hostfwd_add n0 tcp:127.0.0.1:8765-10.0.2.15:8765", Qmp.forwardCommand("hostfwd_add", "127.0.0.1", 8765))
        assertEquals("hostfwd_remove n0 tcp:0.0.0.0:8765", Qmp.forwardRemoveCommand("0.0.0.0", 8765))
    }
}
