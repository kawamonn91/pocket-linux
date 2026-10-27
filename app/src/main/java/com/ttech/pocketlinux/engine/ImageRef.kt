package com.ttech.pocketlinux.engine

/**
 * `ubuntu:24.04`、`ghcr.io/owner/name:tag`、`python@sha256:...` のようなイメージ名。
 * Docker と同じ規則で、レジストリ省略時は Docker Hub、公式イメージは `library/` を補う。
 */
data class ImageRef(
    /** 接続先のホスト名（Docker Hub は registry-1.docker.io） */
    val registry: String,
    val repository: String,
    val tag: String?,
    val digest: String?,
) {
    /** マニフェストを取得するときの参照（ダイジェスト指定を優先） */
    val reference: String get() = digest ?: tag ?: "latest"

    /** 画面に出す名前（Docker Hub の公式イメージは短く） */
    val displayName: String
        get() {
            val repo = when {
                registry != DOCKER_HUB_HOST -> "$registry/$repository"
                repository.startsWith("library/") -> repository.removePrefix("library/")
                else -> repository
            }
            return when {
                digest != null -> "$repo@$digest"
                else -> "$repo:${tag ?: "latest"}"
            }
        }

    companion object {
        const val DOCKER_HUB_HOST = "registry-1.docker.io"

        private val REPO_COMPONENT = Regex("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*")
        private val TAG = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        private val DIGEST = Regex("sha256:[a-f0-9]{64}")

        fun parse(input: String): ImageRef {
            var rest = input.trim()
            require(rest.isNotEmpty()) { "イメージ名が空です" }
            require(rest.none { it.isWhitespace() }) { "イメージ名に空白は使えません" }

            var digest: String? = null
            val at = rest.indexOf('@')
            if (at >= 0) {
                digest = rest.substring(at + 1)
                require(DIGEST.matches(digest)) { "ダイジェストの形式が不正です: $digest" }
                rest = rest.substring(0, at)
            }

            var tag: String? = null
            val lastSlash = rest.lastIndexOf('/')
            val colon = rest.lastIndexOf(':')
            if (colon > lastSlash) {
                tag = rest.substring(colon + 1)
                require(TAG.matches(tag)) { "タグの形式が不正です: $tag" }
                rest = rest.substring(0, colon)
            }

            // 先頭の要素に . か : があるか localhost なら、レジストリのホスト名とみなす（Docker と同じ）
            val firstSlash = rest.indexOf('/')
            var registry = DOCKER_HUB_HOST
            if (firstSlash > 0) {
                val first = rest.substring(0, firstSlash)
                if (first.contains('.') || first.contains(':') || first == "localhost") {
                    registry = if (first == "docker.io" || first == "index.docker.io") DOCKER_HUB_HOST else first
                    rest = rest.substring(firstSlash + 1)
                }
            }
            if (registry == DOCKER_HUB_HOST && !rest.contains('/')) rest = "library/$rest"

            require(rest.isNotEmpty()) { "リポジトリ名が空です" }
            rest.split('/').forEach { part ->
                require(REPO_COMPONENT.matches(part)) { "リポジトリ名に使えない文字があります: $part" }
            }
            if (tag == null && digest == null) tag = "latest"
            return ImageRef(registry, rest, tag, digest)
        }
    }
}
