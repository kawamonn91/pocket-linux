package com.ttech.pocketlinux.engine

/**
 * /etc/passwd・/etc/group・/etc/shadow に一般ユーザーを足す。
 * useradd が無いイメージ（Alpine など）でも動くよう、ファイルを直接書き換える。
 */
object Accounts {

    data class Result(val passwd: String, val group: String, val shadow: String?, val uid: Int)

    /**
     * @param androidGids アプリのプロセスが属する Android のグループ。
     *   名前が無いと `groups: cannot find name for group ID 3003` と出るため、`aid_3003` のような名前を付ける
     * @param daysSinceEpoch /etc/shadow の「最後にパスワードを変えた日」
     */
    fun addUser(
        passwd: String,
        group: String,
        shadow: String?,
        user: String,
        home: String,
        shell: String,
        androidGids: List<Int>,
        daysSinceEpoch: Long,
    ): Result {
        val passwdLines = lines(passwd)
        val groupLines = lines(group)
        val existing = passwdLines.firstOrNull { it.substringBefore(':') == user }

        val uid: Int
        val newPasswd: List<String>
        if (existing != null) {
            uid = existing.split(':').getOrNull(2)?.toIntOrNull() ?: 1000
            // シェルだけは後から入った bash に合わせて直す
            newPasswd = passwdLines.map { line ->
                if (line.substringBefore(':') != user) return@map line
                val fields = line.split(':').toMutableList()
                while (fields.size < 7) fields += ""
                fields[6] = shell
                fields.joinToString(":")
            }
        } else {
            val usedIds = (passwdLines.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() } +
                groupLines.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }).toSet()
            uid = generateSequence(1000) { it + 1 }.first { it !in usedIds }
            newPasswd = passwdLines + "$user:x:$uid:$uid:Pocket Linux:$home:$shell"
        }

        val newGroup = groupLines.toMutableList()
        if (newGroup.none { it.substringBefore(':') == user }) newGroup += "$user:x:$uid:"
        val knownGids = newGroup.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toMutableSet()
        for (gid in androidGids.distinct()) {
            if (gid in knownGids || gid == 0) continue
            newGroup += "aid_$gid:x:$gid:"
            knownGids += gid
        }

        val newShadow = shadow?.let { text ->
            val shadowLines = lines(text)
            if (shadowLines.any { it.substringBefore(':') == user }) shadowLines
            else shadowLines + "$user:!:$daysSinceEpoch:0:99999:7:::"
        }

        return Result(
            passwd = join(newPasswd),
            group = join(newGroup),
            shadow = newShadow?.let(::join),
            uid = uid,
        )
    }

    private fun lines(text: String) = text.lines().filter { it.isNotBlank() }
    private fun join(lines: List<String>) = lines.joinToString("\n", postfix = "\n")
}
