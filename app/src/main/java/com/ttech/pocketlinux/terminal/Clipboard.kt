package com.ttech.pocketlinux.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** 端末のコピー・貼り付けで使うクリップボード */
object Clipboard {
    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pocket Linux", text))
    }

    fun read(context: Context): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}
