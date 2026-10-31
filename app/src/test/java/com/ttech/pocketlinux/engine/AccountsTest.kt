package com.ttech.pocketlinux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {

    private val basePasswd = """
        root:x:0:0:root:/root:/bin/bash
        daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
    """.trimIndent()

    private val baseGroup = """
        root:x:0:
        daemon:x:1:
    """.trimIndent()

    private val baseShadow = """
        root:*:19000:0:99999:7:::
    """.trimIndent()

    @Test
    fun addUser_新規ユーザーを追加_uidは1000から割り当て() {
        val result = Accounts.addUser(
            passwd = basePasswd,
            group = baseGroup,
            shadow = baseShadow,
            user = "developer",
            home = "/home/developer",
            shell = "/bin/bash",
            androidGids = listOf(3003, 1015, 3003, 0),
            daysSinceEpoch = 20758L,
        )

        assertEquals(1000, result.uid)

        // passwd の確認
        assertTrue(result.passwd.contains("developer:x:1000:1000:Pocket Linux:/home/developer:/bin/bash\n"))

        // group の確認 (ユーザーグループ + aid_ グループ、gid 0 はスキップ、重複排除)
        assertTrue(result.group.contains("developer:x:1000:\n"))
        assertTrue(result.group.contains("aid_3003:x:3003:\n"))
        assertTrue(result.group.contains("aid_1015:x:1015:\n"))
        assertEquals(1, result.group.split("aid_3003").size - 1)

        // shadow の確認
        assertNotNull(result.shadow)
        assertTrue(result.shadow!!.contains("developer:!:20758:0:99999:7:::\n"))
    }

    @Test
    fun addUser_1000が使用済みの場合は次のuidを割り当て() {
        val passwdWith1000 = basePasswd + "\nexisting:x:1000:1000:Existing:/home/existing:/bin/sh"
        val result = Accounts.addUser(
            passwd = passwdWith1000,
            group = baseGroup,
            shadow = baseShadow,
            user = "newUser",
            home = "/home/newUser",
            shell = "/bin/zsh",
            androidGids = emptyList(),
            daysSinceEpoch = 20000L,
        )

        assertEquals(1001, result.uid)
        assertTrue(result.passwd.contains("newUser:x:1001:1001:Pocket Linux:/home/newUser:/bin/zsh\n"))
    }

    @Test
    fun addUser_既存ユーザーのシェルを更新() {
        val existingPasswd = basePasswd + "\nuser1:x:1005:1005:Pocket Linux:/home/user1:/bin/sh"
        val existingGroup = baseGroup + "\nuser1:x:1005:"
        val existingShadow = baseShadow + "\nuser1:hash:18000:0:99999:7:::"

        val result = Accounts.addUser(
            passwd = existingPasswd,
            group = existingGroup,
            shadow = existingShadow,
            user = "user1",
            home = "/home/user1",
            shell = "/bin/bash",
            androidGids = listOf(3003),
            daysSinceEpoch = 20758L,
        )

        assertEquals(1005, result.uid)
        // シェルが /bin/bash に更新されている
        assertTrue(result.passwd.contains("user1:x:1005:1005:Pocket Linux:/home/user1:/bin/bash\n"))
        // shadow は既存のハッシュを保持し、再追加されない
        assertNotNull(result.shadow)
        val shadow = result.shadow!!
        assertTrue(shadow.contains("user1:hash:18000:0:99999:7:::\n"))
        assertEquals(1, shadow.split("user1:").size - 1)
    }

    @Test
    fun addUser_shadowがnullの場合は結果もnull() {
        val result = Accounts.addUser(
            passwd = basePasswd,
            group = baseGroup,
            shadow = null,
            user = "alpineUser",
            home = "/home/alpineUser",
            shell = "/bin/sh",
            androidGids = emptyList(),
            daysSinceEpoch = 20758L,
        )

        assertNull(result.shadow)
        assertTrue(result.passwd.contains("alpineUser:x:1000:1000:Pocket Linux:/home/alpineUser:/bin/sh\n"))
    }
}
