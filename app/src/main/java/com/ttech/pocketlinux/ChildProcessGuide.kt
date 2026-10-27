package com.ttech.pocketlinux

import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * Android 12 以降の「子プロセスの制限」（phantom process killer）の案内。
 * アプリ全体で子プロセスが 32 個を超えると、Android が古いものから強制終了する。
 * コンテナ内の各プロセスは本物のプロセスなので、Claude Code などを動かすとすぐに超える。
 */
object ChildProcessGuide {
    private const val KEY = "phantom_notice_dismissed"

    fun isDismissed(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun dismiss(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { putBoolean(KEY, true) }

    fun message(): String = if (Build.VERSION.SDK_INT >= 34) {
        "コンテナの中のプロセスが多いと、Android が勝手に終了させます。" +
            "設定 → システム → 開発者向けオプション →「子プロセスの制限を無効にする」をオンにしてください" +
            "（開発者向けオプションは、端末情報のビルド番号を7回タップすると出ます）。"
    } else {
        "コンテナの中のプロセスが多いと、Android が勝手に終了させます。PC から adb で次の2行を実行して制限を外してください:\n" +
            ADB_COMMANDS
    }

    const val ADB_COMMANDS =
        "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\"\n" +
            "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
}
