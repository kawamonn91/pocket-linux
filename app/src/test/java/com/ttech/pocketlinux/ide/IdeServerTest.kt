package com.ttech.pocketlinux.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeServerTest {

    @Test
    fun VMの中では全部の宛先で待ち受ける() {
        val script = IdeServer.startScript(8765, "tok_en-123")
        assertTrue(script.contains("--bind 0.0.0.0 --port 8765"))
        assertTrue(script.contains("cd /opt/pocket-dev"))
        assertTrue(script.contains("--root \"\$HOME/drive\""))
    }

    @Test
    fun トークンは引数ではなく環境変数で渡す() {
        val script = IdeServer.startScript(8765, "tok_en-123")
        assertTrue(script.contains("export POCKETDEV_TOKEN='tok_en-123'"))
        assertFalse(script.contains("--token"))
    }

    @Test
    fun Pythonが無ければsudoで入れる() {
        val script = IdeServer.startScript(8765, "t")
        assertTrue(script.contains("command -v python3"))
        assertTrue(script.contains("sudo -n apt-get install -y --no-install-recommends python3"))
    }
}
