package com.ttech.pocketlinux.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Drive のフォルダと、端末（コンテナの ~/drive/<名前>）のフォルダの組 */
@Serializable
data class SyncPair(
    val id: String,
    /** Drive の中のフォルダ（マイドライブからのパス。例: dev/app） */
    val remotePath: String,
    /** 端末側のフォルダ名。コンテナの中では ~/drive/<名前> に見える */
    val localName: String,
    val createdAt: Long,
    /** 最初の同期（両方の中身を合わせる）がまだか、やり直しが必要 */
    val needsResync: Boolean = true,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
)

/** 同期する組の一覧を JSON で保存する */
class SyncPairStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(SyncPair.serializer())

    fun load(): List<SyncPair> =
        if (file.isFile) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()) else emptyList()

    fun save(pairs: List<SyncPair>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, pairs))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** 同期しないもの（rclone のフィルタ。--exclude-from に渡す） */
object Excludes {
    val DEFAULT = """
        |# 同期しないもの。1行に1つ、rclone のフィルタの書き方（https://rclone.org/filtering/）
        |# 「名前/**」はどの深さにあるフォルダでも中身ごと除く
        |node_modules/**
        |.venv/**
        |venv/**
        |__pycache__/**
        |.gradle/**
        |.l2s.*
        |.DS_Store
        |""".trimMargin()

    /** フォルダ名のあとに「/」と「**」が続く形の行から、見張らなくてよいフォルダ名を取り出す（変更の見張りを軽くするため） */
    fun skippedDirNames(text: String): Set<String> =
        text.lineSequence()
            .map { it.trim() }
            .mapNotNull { Regex("""^([^/*?\[\]{}#!]+)/\*\*$""").matchEntire(it)?.groupValues?.get(1) }
            .toSet()

    /** 端末側のフォルダ名として使えるか（パスの区切りや特殊な名前は使わせない） */
    fun isValidLocalName(name: String): Boolean =
        name.isNotBlank() && name == name.trim() && name != "." && name != ".." && name.length <= 100 &&
            name.none { it == '/' || it == '\\' || it.code < 0x20 }
}
