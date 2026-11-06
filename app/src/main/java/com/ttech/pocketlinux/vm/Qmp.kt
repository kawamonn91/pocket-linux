package com.ttech.pocketlinux.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * QEMU の制御用ソケット（QMP）。動いている VM のポート転送を足し引きする。
 * 必要なときだけつなぎ、使い終わったら閉じる。
 */
class Qmp(private val socketPath: File) {

    /** 端末の [hostAddress]:[port] への接続を、ゲストの同じポートへ転送する */
    fun addForward(hostAddress: String, port: Int) {
        val result = hmp(forwardCommand("hostfwd_add", hostAddress, port))
        if (result.isNotBlank()) throw IOException("ポート $port を転送できません: ${result.trim()}")
    }

    fun removeForward(hostAddress: String, port: Int) {
        hmp(forwardRemoveCommand(hostAddress, port))
    }

    /** ハイパーバイザーを止めずに QEMU の内部のモニターのコマンドを実行する。応答の文字列を返す */
    private fun hmp(command: String): String {
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath.path, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 5_000
            val reader = socket.inputStream.bufferedReader()
            val writer = socket.outputStream.bufferedWriter()
            reader.readLine() // {"QMP": ...} のあいさつ
            writer.write("""{"execute":"qmp_capabilities"}""" + "\n")
            writer.flush()
            readResponse(reader::readLine)
            val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
            writer.write("""{"execute":"human-monitor-command","arguments":{"command-line":"$escaped"}}""" + "\n")
            writer.flush()
            return readResponse(reader::readLine)["return"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    /** イベントを読み飛ばして、次の応答を返す */
    private fun readResponse(readLine: () -> String?): JsonObject {
        while (true) {
            val line = readLine() ?: throw IOException("QEMU との接続が切れました")
            val body = Json.parseToJsonElement(line).jsonObject
            body["error"]?.let { throw IOException("QEMU: $it") }
            if ("return" in body) return body
        }
    }

    companion object {
        fun forwardCommand(verb: String, hostAddress: String, port: Int) =
            "$verb ${QemuLauncher.NET_ID} tcp:$hostAddress:$port-${QemuLauncher.GUEST_ADDRESS}:$port"

        fun forwardRemoveCommand(hostAddress: String, port: Int) =
            "hostfwd_remove ${QemuLauncher.NET_ID} tcp:$hostAddress:$port"
    }
}
