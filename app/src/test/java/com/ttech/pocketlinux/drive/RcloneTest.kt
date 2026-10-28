package com.ttech.pocketlinux.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RcloneTest {

    @Test
    fun parseToken_貼り付け用のトークンを取り出す() {
        val output = """
            NOTICE: Please go to the following link: http://127.0.0.1:53682/auth?state=abc
            NOTICE: Got code
            Paste the following into your remote machine --->
            {"access_token":"ya29.x","token_type":"Bearer","refresh_token":"1//y","expiry":"2026-10-28T12:00:00Z"}
            <---End paste
        """.trimIndent()
        assertEquals(
            """{"access_token":"ya29.x","token_type":"Bearer","refresh_token":"1//y","expiry":"2026-10-28T12:00:00Z"}""",
            Rclone.parseToken(output),
        )
    }

    @Test
    fun parseToken_失敗したときはnull() {
        assertNull(Rclone.parseToken("ERROR : Failed to get token: oauth2: cannot fetch token"))
        assertNull(Rclone.parseToken("--->\nnot json\n<---End paste"))
        assertNull(Rclone.parseToken("--->\n{\"error\":\"x\"}\n<---End paste"))
    }

    @Test
    fun parseFolderNames_フォルダ名を名前順に返す() {
        val json = """[{"Path":"src","Name":"src","IsDir":true},{"Path":"App","Name":"App","IsDir":true},{"Path":"a.txt","Name":"a.txt","IsDir":false}]"""
        assertEquals(listOf("App", "src"), Rclone.parseFolderNames(json))
        assertEquals(emptyList<String>(), Rclone.parseFolderNames("[]"))
    }

    @Test
    fun remote_前後のスラッシュを取る() {
        assertEquals("gdrive:", Rclone.remote(""))
        assertEquals("gdrive:dev/app", Rclone.remote("/dev/app/"))
    }

    @Test
    fun bisyncArgs_初回だけresyncを付ける() {
        val args = Rclone.bisyncArgs("dev/app", File("/l"), File("/w"), File("/e"), resync = false)
        assertEquals(listOf("bisync", "gdrive:dev/app", File("/l").path), args.take(3))
        assertTrue("--copy-links" in args)
        assertTrue("--drive-skip-gdocs" in args)
        assertFalse("--resync" in args)
        val first = Rclone.bisyncArgs("dev/app", File("/l"), File("/w"), File("/e"), resync = true)
        assertTrue(first.containsAll(listOf("--resync", "--resync-mode", "newer")))
    }
}
