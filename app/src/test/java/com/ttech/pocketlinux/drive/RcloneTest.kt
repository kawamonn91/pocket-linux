package com.ttech.pocketlinux.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneTest {

    private val client = DriveClient("id-1.apps.googleusercontent.com", "secret-1")

    @Test
    fun DriveClient_IDと秘密鍵がそろって初めて設定済みになる() {
        assertTrue(client.configured)
        assertFalse(DriveClient("", "").configured)
        assertFalse(DriveClient("id", "").configured)
        assertFalse(DriveClient("", "secret").configured)
    }

    @Test
    fun authorizeArgs_自前のクライアントを渡す() {
        assertEquals(
            listOf("authorize", "drive", "id-1.apps.googleusercontent.com", "secret-1", "--auth-no-open-browser"),
            Rclone.authorizeArgs(client),
        )
    }

    @Test
    fun configText_クライアントとトークンを書く() {
        assertEquals(
            "[gdrive]\ntype = drive\nscope = drive\nclient_id = id-1.apps.googleusercontent.com\nclient_secret = secret-1\n" +
                "token = {\"access_token\":\"x\"}\n",
            Rclone.configText(client, """{"access_token":"x"}"""),
        )
    }

    @Test
    fun isLoggedInWith_今のクライアントの目印だけをログイン済みとみなす() {
        assertTrue(Rclone.isLoggedInWith("id-1.apps.googleusercontent.com\n", client))
        // 目印が無い・2.0.0 までの空の目印（rclone 共有のクライアント）・別のクライアント
        assertFalse(Rclone.isLoggedInWith(null, client))
        assertFalse(Rclone.isLoggedInWith("", client))
        assertFalse(Rclone.isLoggedInWith("other.apps.googleusercontent.com", client))
        // ログインの設定が入っていないビルド
        assertFalse(Rclone.isLoggedInWith("", DriveClient("", "")))
    }

    @Test
    fun loginError_知っている失敗の理由だけを言い換える() {
        assertEquals("Google で許可されませんでした", Rclone.loginError("oauth2: access_denied"))
        assertTrue(Rclone.loginError("oauth2: invalid_client")!!.contains("新しい版"))
        assertNull(Rclone.loginError("something else"))
    }

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
        val args = Rclone.bisyncArgs("dev/app", "/l", "/w", "/e", resync = false)
        assertEquals(listOf("bisync", "gdrive:dev/app", "/l"), args.take(3))
        assertTrue("--copy-links" in args)
        assertTrue("--drive-skip-gdocs" in args)
        assertFalse("--resync" in args)
        val first = Rclone.bisyncArgs("dev/app", "/l", "/w", "/e", resync = true)
        assertTrue(first.containsAll(listOf("--resync", "--resync-mode", "newer")))
    }
}
