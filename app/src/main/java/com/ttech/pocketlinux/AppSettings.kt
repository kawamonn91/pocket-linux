package com.ttech.pocketlinux

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** 画面で変えられる設定（SharedPreferences に保存し、変化は StateFlow で通知する） */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(
        val fontSizeSp: Int,
        /** 一部のキーボード（Samsung など）で文字が二重に入る問題の回避 */
        val enforceCharInput: Boolean,
        val vibrateOnBell: Boolean,
        val ideEnabled: Boolean,
        val ideContainerId: String?,
        val ideAllowLan: Boolean,
        val idePort: Int,
        val ideToken: String,
        val keepAwake: Boolean,
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()
    val current: Values get() = _values.value

    private fun read(): Values {
        if (!prefs.contains(KEY_IDE_TOKEN)) prefs.edit { putString(KEY_IDE_TOKEN, newToken()) }
        return Values(
            fontSizeSp = prefs.getInt(KEY_FONT, 14),
            enforceCharInput = prefs.getBoolean(KEY_CHAR_INPUT, false),
            vibrateOnBell = prefs.getBoolean(KEY_BELL, true),
            ideEnabled = prefs.getBoolean(KEY_IDE_ENABLED, false),
            ideContainerId = prefs.getString(KEY_IDE_CONTAINER, null),
            ideAllowLan = prefs.getBoolean(KEY_IDE_LAN, false),
            idePort = prefs.getInt(KEY_IDE_PORT, 8765),
            ideToken = prefs.getString(KEY_IDE_TOKEN, null) ?: newToken(),
            keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, false),
        )
    }

    fun update(change: (Values) -> Values) {
        val next = change(_values.value)
        prefs.edit {
            putInt(KEY_FONT, next.fontSizeSp)
            putBoolean(KEY_CHAR_INPUT, next.enforceCharInput)
            putBoolean(KEY_BELL, next.vibrateOnBell)
            putBoolean(KEY_IDE_ENABLED, next.ideEnabled)
            putString(KEY_IDE_CONTAINER, next.ideContainerId)
            putBoolean(KEY_IDE_LAN, next.ideAllowLan)
            putInt(KEY_IDE_PORT, next.idePort)
            putString(KEY_IDE_TOKEN, next.ideToken)
            putBoolean(KEY_KEEP_AWAKE, next.keepAwake)
        }
        _values.value = next
    }

    fun regenerateIdeToken() = update { it.copy(ideToken = newToken()) }

    companion object {
        const val MIN_FONT = 8
        const val MAX_FONT = 32

        private const val KEY_FONT = "font_size_sp"
        private const val KEY_CHAR_INPUT = "enforce_char_input"
        private const val KEY_BELL = "vibrate_on_bell"
        private const val KEY_IDE_ENABLED = "ide_enabled"
        private const val KEY_IDE_CONTAINER = "ide_container"
        private const val KEY_IDE_LAN = "ide_allow_lan"
        private const val KEY_IDE_PORT = "ide_port"
        private const val KEY_IDE_TOKEN = "ide_token"
        private const val KEY_KEEP_AWAKE = "keep_awake"

        /** pocket-dev の auth.generate_token と同じく 24 バイトの URL 安全な文字列 */
        fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        }
    }
}
