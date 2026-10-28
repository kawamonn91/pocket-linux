package com.ttech.pocketlinux.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncPairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun 組の一覧を保存して読み直せる() {
        val store = SyncPairStore(tmp.root.resolve("rclone/pairs.json"))
        assertEquals(emptyList<SyncPair>(), store.load())
        val pairs = listOf(
            SyncPair(id = "a1", remotePath = "dev/app", localName = "app", createdAt = 1L),
            SyncPair(id = "b2", remotePath = "メモ", localName = "メモ", createdAt = 2L, needsResync = false, lastSyncAt = 3L, lastError = "x"),
        )
        store.save(pairs)
        assertEquals(pairs, store.load())
    }

    @Test
    fun 壊れたファイルは空として読む() {
        val file = tmp.root.resolve("pairs.json").apply { writeText("{broken") }
        assertEquals(emptyList<SyncPair>(), SyncPairStore(file).load())
    }

    @Test
    fun skippedDirNames_名前だけのフォルダの除外を拾う() {
        assertEquals(
            setOf("node_modules", ".venv", "venv", "__pycache__", ".gradle"),
            Excludes.skippedDirNames(Excludes.DEFAULT),
        )
        // パスつき・ワイルドカードつき・コメントは見張りの対象から外さない
        assertEquals(emptySet<String>(), Excludes.skippedDirNames("# x/**\n/build/**\nsrc/gen/**\n*.tmp/**\n"))
    }

    @Test
    fun isValidLocalName_パスの区切りや特殊な名前は使えない() {
        assertTrue(Excludes.isValidLocalName("app"))
        assertTrue(Excludes.isValidLocalName("日本語 フォルダ"))
        assertFalse(Excludes.isValidLocalName(""))
        assertFalse(Excludes.isValidLocalName(" app"))
        assertFalse(Excludes.isValidLocalName("."))
        assertFalse(Excludes.isValidLocalName(".."))
        assertFalse(Excludes.isValidLocalName("a/b"))
        assertFalse(Excludes.isValidLocalName("a\\b"))
        assertFalse(Excludes.isValidLocalName("a\nb"))
    }
}
