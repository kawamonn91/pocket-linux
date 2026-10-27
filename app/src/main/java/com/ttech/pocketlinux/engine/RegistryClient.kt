package com.ttech.pocketlinux.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** レジストリとのやり取りで、利用者に見せてよい失敗 */
class RegistryException(message: String) : IOException(message)

/** コンテナの CPU の種類（OCI の platform.architecture / variant） */
data class Platform(val architecture: String, val variant: String?) {
    companion object {
        /** Android の ABI 名から決める */
        fun fromAbi(abi: String): Platform = when (abi) {
            "arm64-v8a" -> Platform("arm64", "v8")
            "x86_64" -> Platform("amd64", null)
            else -> throw IllegalArgumentException("この端末の CPU（$abi）には対応していません")
        }
    }
}

data class Layer(val digest: String, val size: Long, val mediaType: String) {
    val isGzip: Boolean get() = mediaType.endsWith("gzip")
}

/** イメージの設定のうち、コンテナの起動に使う部分 */
data class ImageConfig(
    val env: List<String> = emptyList(),
    val workingDir: String? = null,
    val user: String? = null,
    val architecture: String? = null,
)

data class ResolvedImage(
    val ref: ImageRef,
    val manifestDigest: String,
    val layers: List<Layer>,
    val config: ImageConfig,
)

/**
 * Docker Registry HTTP API v2（OCI Distribution）の最小限のクライアント。
 * 公開イメージの pull だけに対応する（匿名のトークン認証は自動で行う）。
 */
class RegistryClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = mutableMapOf<String, String>()

    fun resolve(ref: ImageRef, platform: Platform): ResolvedImage {
        val (firstType, firstBody, firstDigest) = fetchManifest(ref, ref.reference)
        var manifest = json.parseToJsonElement(firstBody).jsonObject
        var mediaType = manifest.string("mediaType") ?: firstType
        var manifestDigest = firstDigest

        if (isIndex(mediaType, manifest)) {
            val chosen = selectPlatform(manifest, platform)
                ?: throw RegistryException(
                    "${ref.displayName} には ${platform.architecture} 版がありません（スマホ・タブレットでは arm64 版が必要です）"
                )
            val (type, body, digest) = fetchManifest(ref, chosen)
            manifest = json.parseToJsonElement(body).jsonObject
            mediaType = manifest.string("mediaType") ?: type
            manifestDigest = digest
        }

        if (mediaType !in IMAGE_MANIFEST_TYPES && manifest["layers"] == null) {
            throw RegistryException("対応していない形式のイメージです（$mediaType）")
        }

        val configDigest = manifest["config"]?.jsonObject?.string("digest")
            ?: throw RegistryException("イメージの設定が見つかりません")
        val configJson = json.parseToJsonElement(fetchBlobText(ref, configDigest)).jsonObject
        val config = parseConfig(configJson)
        if (config.architecture != null && config.architecture != platform.architecture) {
            throw RegistryException(
                "${ref.displayName} は ${config.architecture} 用です（この端末は ${platform.architecture}）"
            )
        }

        val layers = manifest["layers"]?.jsonArray.orEmpty().map { element ->
            val obj = element.jsonObject
            Layer(
                digest = obj.string("digest") ?: throw RegistryException("レイヤーの情報が不正です"),
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                mediaType = obj.string("mediaType") ?: "application/vnd.oci.image.layer.v1.tar+gzip",
            )
        }
        layers.forEach { layer ->
            if (layer.mediaType.contains("zstd")) {
                throw RegistryException("zstd で圧縮されたイメージには対応していません")
            }
            if (layer.mediaType.contains("foreign") || layer.mediaType.contains("nondistributable")) {
                throw RegistryException("このイメージは配布できないレイヤーを含んでいます")
            }
        }
        return ResolvedImage(ref, manifestDigest, layers, config)
    }

    /**
     * レイヤーをダウンロードして SHA-256 を確かめる。途中まで落ちている場合は続きから取得する。
     * @param onProgress (取得済みバイト数, 全体のバイト数)
     */
    fun downloadBlob(ref: ImageRef, layer: Layer, dest: File, onProgress: (Long, Long) -> Unit) {
        if (dest.exists() && sha256(dest) == layer.digest) {
            onProgress(layer.size, layer.size)
            return
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var offset = if (part.exists()) part.length() else 0L

        val builder = Request.Builder().url(blobUrl(ref, layer.digest))
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        executeAuthorized(ref, builder).use { response ->
            if (response.code == 416) {
                // 取得済みの長さが合わない（途中のファイルが壊れている）ので最初からやり直す
                part.delete()
                offset = 0
                return downloadBlob(ref, layer, dest, onProgress)
            }
            if (!response.isSuccessful) throw RegistryException("レイヤーを取得できません（HTTP ${response.code}）")
            val append = response.code == 206
            if (!append) offset = 0
            val total = if (layer.size > 0) layer.size else offset + (response.body.contentLength().takeIf { it > 0 } ?: 0)

            FileOutputStream(part, append).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = offset
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (done - lastReport >= 256 * 1024) {
                            onProgress(done, total)
                            lastReport = done
                        }
                    }
                    onProgress(done, total)
                }
            }
        }

        val actual = sha256(part)
        if (actual != layer.digest) {
            part.delete()
            throw RegistryException("ダウンロードしたレイヤーが壊れています（ハッシュ不一致）。もう一度お試しください")
        }
        if (!part.renameTo(dest)) throw IOException("レイヤーを保存できません: $dest")
    }

    // ---------------------------------------------------------------- 内部

    private data class ManifestResponse(val mediaType: String, val body: String, val digest: String)

    private fun fetchManifest(ref: ImageRef, reference: String): ManifestResponse {
        val builder = Request.Builder()
            .url("https://${ref.registry}/v2/${ref.repository}/manifests/$reference")
            .header("Accept", MANIFEST_ACCEPT)
        executeAuthorized(ref, builder).use { response ->
            when (response.code) {
                200 -> Unit
                404 -> throw RegistryException("イメージが見つかりません: ${ref.displayName}")
                401, 403 -> throw RegistryException("このイメージは取得できません（非公開か、名前が間違っています）: ${ref.displayName}")
                429 -> throw RegistryException("Docker Hub の取得回数の上限に達しました。しばらく待ってからお試しください")
                else -> throw RegistryException("イメージの情報を取得できません（HTTP ${response.code}）")
            }
            val bytes = response.body.bytes()
            val digest = response.header("Docker-Content-Digest") ?: ("sha256:" + sha256Hex(bytes))
            val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            return ManifestResponse(type, bytes.toString(Charsets.UTF_8), digest)
        }
    }

    private fun fetchBlobText(ref: ImageRef, digest: String): String {
        executeAuthorized(ref, Request.Builder().url(blobUrl(ref, digest))).use { response ->
            if (!response.isSuccessful) throw RegistryException("イメージの設定を取得できません（HTTP ${response.code}）")
            val bytes = response.body.bytes()
            if ("sha256:" + sha256Hex(bytes) != digest) throw RegistryException("イメージの設定が壊れています")
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun blobUrl(ref: ImageRef, digest: String) = "https://${ref.registry}/v2/${ref.repository}/blobs/$digest"

    /** 401 が返ったら WWW-Authenticate の指示どおりに匿名トークンを取得してやり直す */
    private fun executeAuthorized(ref: ImageRef, builder: Request.Builder): Response {
        val key = "${ref.registry}/${ref.repository}"
        tokens[key]?.let { builder.header("Authorization", "Bearer $it") }
        val response = http.newCall(builder.build()).execute()
        if (response.code != 401) return response

        val challenge = response.header("WWW-Authenticate")
        response.close()
        val token = fetchToken(challenge ?: throw RegistryException("レジストリの認証方式が分かりません"))
        tokens[key] = token
        builder.header("Authorization", "Bearer $token")
        return http.newCall(builder.build()).execute()
    }

    private fun fetchToken(challenge: String): String {
        val params = parseChallenge(challenge)
            ?: throw RegistryException("このレジストリの認証方式には対応していません")
        val realm = params["realm"] ?: throw RegistryException("レジストリの認証先が分かりません")
        val url = realm.toHttpUrl().newBuilder().apply {
            params["service"]?.let { addQueryParameter("service", it) }
            params["scope"]?.let { addQueryParameter("scope", it) }
        }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw RegistryException("レジストリの認証に失敗しました（HTTP ${response.code}）")
            val obj = json.parseToJsonElement(response.body.string()).jsonObject
            return obj.string("token") ?: obj.string("access_token")
                ?: throw RegistryException("レジストリの認証に失敗しました")
        }
    }

    companion object {
        private val INDEX_TYPES = setOf(
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.index.v1+json",
        )
        private val IMAGE_MANIFEST_TYPES = setOf(
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
        )
        private val MANIFEST_ACCEPT = (INDEX_TYPES + IMAGE_MANIFEST_TYPES).joinToString(", ")

        private fun isIndex(mediaType: String, manifest: JsonObject) =
            mediaType in INDEX_TYPES || (manifest["manifests"] != null && manifest["layers"] == null)

        /** マルチアーキテクチャのイメージから、この端末向けのマニフェストを選ぶ */
        fun selectPlatform(index: JsonObject, platform: Platform): String? {
            val candidates = (index["manifests"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                val p = obj["platform"]?.jsonObject ?: return@mapNotNull null
                if (p.string("os") != "linux" || p.string("architecture") != platform.architecture) return@mapNotNull null
                obj.string("digest")?.let { it to p.string("variant") }
            }
            return candidates.firstOrNull { it.second == platform.variant }?.first
                ?: candidates.firstOrNull { it.second == null }?.first
                ?: candidates.firstOrNull()?.first
        }

        fun parseConfig(configJson: JsonObject): ImageConfig {
            val config = configJson["config"] as? JsonObject
            return ImageConfig(
                env = (config?.get("Env") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                workingDir = config?.string("WorkingDir")?.takeIf { it.isNotEmpty() },
                user = config?.string("User")?.takeIf { it.isNotEmpty() },
                architecture = configJson.string("architecture"),
            )
        }

        /** `Bearer realm="...",service="...",scope="..."` を分解する（Bearer 以外は null） */
        fun parseChallenge(header: String): Map<String, String>? {
            val trimmed = header.trim()
            if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
            val result = mutableMapOf<String, String>()
            Regex("""(\w+)="([^"]*)"""").findAll(trimmed.substring(7)).forEach { match ->
                result[match.groupValues[1].lowercase()] = match.groupValues[2]
            }
            return result
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun sha256Hex(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    }
}
