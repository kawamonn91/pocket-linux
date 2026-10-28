package com.ttech.pocketlinux.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeServerTest {

    @Test
    fun 既定は端末の中だけで待ち受ける() {
        val script = IdeServer.startScript(8765, "tok_en-123", allowLan = false)
        assertTrue(script.contains("--bind 127.0.0.1 --port 8765"))
        assertTrue(script.contains("cd /opt/pocket-dev"))
        assertTrue(script.contains("--root \"\$HOME/drive\""))
    }

    @Test
    fun LANを許可すると全部の宛先で待ち受ける() {
        assertTrue(IdeServer.startScript(9000, "t", allowLan = true).contains("--bind 0.0.0.0 --port 9000"))
    }

    @Test
    fun トークンは引数ではなく環境変数で渡す() {
        val script = IdeServer.startScript(8765, "tok_en-123", allowLan = false)
        assertTrue(script.contains("export POCKETDEV_TOKEN='tok_en-123'"))
        assertFalse(script.contains("--token"))
    }

    @Test
    fun Pythonが無ければsudoで入れる() {
        val script = IdeServer.startScript(8765, "t", allowLan = false)
        assertTrue(script.contains("command -v python3"))
        assertTrue(script.contains("sudo -n apt-get install -y --no-install-recommends python3"))
    }
}
