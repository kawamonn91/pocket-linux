package com.ttech.pocketlinux.engine

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 読んだバイト数を一定の量ごとに知らせる（進み具合の表示用） */
internal class CountingInputStream(
    input: InputStream,
    private val reportEvery: Long,
    private val onRead: (Long) -> Unit,
) : FilterInputStream(input) {
    private var count = 0L
    private var lastReport = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n.toLong())
        return n
    }

    private fun advance(n: Long) {
        count += n
        if (count - lastReport >= reportEvery) {
            lastReport = count
            onRead(count)
        }
    }
}

/** 書いたバイト数を一定の量ごとに知らせる */
internal class CountingOutputStream(
    output: OutputStream,
    private val reportEvery: Long,
    private val onWrite: (Long) -> Unit,
) : FilterOutputStream(output) {
    private var count = 0L
    private var lastReport = 0L

    override fun write(b: Int) {
        out.write(b)
        advance(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        advance(len.toLong())
    }

    private fun advance(n: Long) {
        count += n
        if (count - lastReport >= reportEvery) {
            lastReport = count
            onWrite(count)
        }
    }
}
