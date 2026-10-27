package com.ttech.pocketlinux.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ContainerConfig(
    val id: String,
    val name: String,
    /** 作成元のイメージ（表示用。例: ubuntu:24.04） */
    val image: String,
    val imageDigest: String? = null,
    val createdAt: Long,
    /** ログインに使う一般ユーザー。null なら root でログインする（sudo を入れられなかった場合など） */
    val user: String? = DEFAULT_USER,
    val home: String = "/home/$DEFAULT_USER",
    /** イメージの ENV（`KEY=VALUE`） */
    val env: List<String> = emptyList(),
    val workingDir: String? = null,
    /** 初期設定（ユーザー作成・sudo のインストール）が終わったか */
    val setupDone: Boolean = false,
    val sudoInstalled: Boolean = false,
    /** コンテナの起動時（最初のターミナルを開いたとき）に実行するコマンド */
    val autostartCommand: String = "",
    val autostartEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_USER = "user"
    }
}

/** コンテナの一覧を container.json から読み書きする */
class ContainerStore(private val paths: Paths) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _all = MutableStateFlow(list())
    /** 画面に出す一覧。保存・削除のたびに更新される */
    val all: StateFlow<List<ContainerConfig>> = _all.asStateFlow()

    private fun refresh() {
        _all.value = list()
    }

    fun list(): List<ContainerConfig> =
        paths.containersDir.listFiles().orEmpty()
            .mapNotNull { dir -> load(dir.name) }
            .sortedBy { it.createdAt }

    fun load(id: String): ContainerConfig? {
        val file = paths.containerConfig(id)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(ContainerConfig.serializer(), file.readText()) }.getOrNull()
    }

    fun save(config: ContainerConfig) {
        val file = paths.containerConfig(config.id)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ContainerConfig.serializer(), config))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        refresh()
    }

    fun update(id: String, change: (ContainerConfig) -> ContainerConfig): ContainerConfig? {
        val current = load(id) ?: return null
        return change(current).also(::save)
    }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

    fun delete(id: String) {
        // 先に設定を消して一覧から外し、時間のかかる中身の削除はその後に行う
        paths.containerConfig(id).delete()
        refresh()
        AndroidFileOps.deleteTree(paths.containerDir(id))
    }

    fun encode(config: ContainerConfig): String = json.encodeToString(ContainerConfig.serializer(), config)

    fun decode(text: String): ContainerConfig = json.decodeFromString(ContainerConfig.serializer(), text)
}
