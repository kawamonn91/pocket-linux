package com.ttech.pocketlinux.terminal

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 端末ビュー（Termux の TerminalView）と画面をつなぐ。
 * 補助キー（CTRL・ALT の押しっぱなし）、物理キーボードのショートカット、文字サイズの変更を扱う。
 */
class TerminalController(
    private val context: Context,
    private val callbacks: Callbacks,
) : TerminalViewClient, SessionManager.Listener {

    interface Callbacks {
        fun newTab(asRoot: Boolean)
        fun closeCurrentTab()
        fun switchTab(offset: Int)
        fun selectTab(index: Int)
        fun changeFontSize(delta: Int)
        fun resetFontSize()
        fun pasteFromClipboard()
        fun vibrateOnBell(): Boolean
        fun enforceCharInput(): Boolean
    }

    var view: TerminalView? = null

    /** 補助キーの CTRL・ALT（次の1文字にだけ効く） */
    var ctrlDown by mutableStateOf(false)
    var altDown by mutableStateOf(false)

    /** 文字入力欄にフォーカスがあるとき、端末ビューに IME の設定をさせない */
    var inputBarFocused = false

    val currentSession: TerminalSession? get() = view?.currentSession

    fun attach(session: TerminalSession) {
        val v = view ?: return
        if (v.attachSession(session)) {
            v.onScreenUpdated()
        }
    }

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
    }

    fun toggleKeyboard() {
        val v = view ?: return
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (v.rootWindowInsets?.let { androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) } == true) {
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } else {
            showKeyboard()
        }
    }

    // ---------------------------------------------------------- 補助キー

    /** 矢印・HOME などの特殊キーを、CTRL・ALT の状態を付けて送る */
    fun sendKey(keyCode: Int) {
        val v = view ?: return
        var mod = 0
        if (ctrlDown) mod = mod or KeyHandler.KEYMOD_CTRL
        if (altDown) mod = mod or KeyHandler.KEYMOD_ALT
        ctrlDown = false
        altDown = false
        v.handleKeyCode(keyCode, mod)
    }

    /** 文字を1つ送る（CTRL・ALT が押されていれば反映） */
    fun sendCodePoint(codePoint: Int) {
        val v = view ?: return
        val ctrl = ctrlDown
        val alt = altDown
        ctrlDown = false
        altDown = false
        v.inputCodePoint(codePoint, ctrl, alt)
    }

    fun sendText(text: String) {
        currentSession?.write(text)
    }

    fun copySelection(): Boolean {
        val v = view ?: return false
        if (!v.isSelectingText) return false
        val text = v.selectedText ?: return false
        Clipboard.copy(context, text)
        v.stopTextSelectionMode()
        return true
    }

    // ---------------------------------------------------------- SessionManager.Listener

    override fun onTextChanged(session: TerminalSession) {
        val v = view ?: return
        if (v.currentSession === session) v.onScreenUpdated()
    }

    override fun onSessionFinished(session: TerminalSession) {
        val v = view ?: return
        if (v.currentSession === session) v.onScreenUpdated()
    }

    override fun onBell(session: TerminalSession) {
        if (!callbacks.vibrateOnBell()) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------------------------------------------------------- TerminalViewClient

    private var scaleAccumulator = 1f

    override fun onScale(scale: Float): Float {
        // ピンチで少しずつ文字サイズを変える（Termux と同じく、ある程度動いたら1段階）
        scaleAccumulator *= scale
        if (scaleAccumulator > 1.15f) {
            callbacks.changeFontSize(+1)
            scaleAccumulator = 1f
        } else if (scaleAccumulator < 0.87f) {
            callbacks.changeFontSize(-1)
            scaleAccumulator = 1f
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape() = false

    override fun shouldEnforceCharBasedInput() = callbacks.enforceCharInput()

    override fun shouldUseCtrlSpaceWorkaround() = false

    override fun isTerminalViewSelected() = !inputBarFocused

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean {
        // 終了したセッションで Enter を押したらタブを閉じる
        if (session != null && !session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
            callbacks.closeCurrentTab()
            return true
        }
        return handleShortcut(keyCode, e)
    }

    /**
     * 物理キーボードのショートカット。シェルや vim が使う Ctrl+英字とぶつからないよう、
     * アプリの操作は Ctrl+Shift か Alt+数字にしている。
     */
    private fun handleShortcut(keyCode: Int, e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed
        val shift = e.isShiftPressed
        val alt = e.isAltPressed
        when {
            ctrl && shift && keyCode == KeyEvent.KEYCODE_T -> callbacks.newTab(asRoot = false)
            ctrl && shift && keyCode == KeyEvent.KEYCODE_R -> callbacks.newTab(asRoot = true)
            ctrl && shift && keyCode == KeyEvent.KEYCODE_W -> callbacks.closeCurrentTab()
            ctrl && keyCode == KeyEvent.KEYCODE_TAB -> callbacks.switchTab(if (shift) -1 else 1)
            ctrl && keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> callbacks.switchTab(1)
            ctrl && keyCode == KeyEvent.KEYCODE_PAGE_UP -> callbacks.switchTab(-1)
            ctrl && shift && keyCode == KeyEvent.KEYCODE_C -> return copySelection()
            ctrl && shift && keyCode == KeyEvent.KEYCODE_V -> callbacks.pasteFromClipboard()
            ctrl && shift && (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS ||
                keyCode == KeyEvent.KEYCODE_SEMICOLON || keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) -> callbacks.changeFontSize(+1)
            ctrl && shift && (keyCode == KeyEvent.KEYCODE_MINUS || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> callbacks.changeFontSize(-1)
            ctrl && shift && keyCode == KeyEvent.KEYCODE_0 -> callbacks.resetFontSize()
            alt && !ctrl && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> callbacks.selectTab(keyCode - KeyEvent.KEYCODE_1)
            else -> return false
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false

    override fun onLongPress(event: MotionEvent?) = false

    override fun readControlKey(): Boolean = ctrlDown.also { if (it) ctrlDown = false }

    override fun readAltKey(): Boolean = altDown.also { if (it) altDown = false }

    override fun readShiftKey() = false

    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message.orEmpty(), e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "TerminalController"
    }
}
