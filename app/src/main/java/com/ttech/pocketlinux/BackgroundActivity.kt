package com.ttech.pocketlinux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** ターミナル以外で常駐サービスを必要とするもの（VM・Drive の同期・IDE のサーバー）の状態 */
class BackgroundActivity {

    data class State(
        val vmRunning: Boolean = false,
        val syncing: Boolean = false,
        val ideRunning: Boolean = false,
        val loggingIn: Boolean = false,
    ) {
        /** VM を使っている（止めないでほしい） */
        val busy: Boolean get() = syncing || ideRunning || loggingIn

        /** VM が動いている間は、裏で止められないよう常駐サービスを動かす */
        val anyRunning: Boolean get() = vmRunning || busy
        val summary: String
            get() = listOfNotNull(
                "Google にログイン中".takeIf { loggingIn },
                "Drive を同期中".takeIf { syncing },
                "IDE のサーバー".takeIf { ideRunning },
            ).joinToString("・")
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val stopHandlers = mutableListOf<() -> Unit>()

    fun update(change: (State) -> State) = _state.update(change)

    /** 通知の「すべて終了」で止めるものを登録する */
    fun onStopAll(handler: () -> Unit) {
        stopHandlers += handler
    }

    fun stopAll() = stopHandlers.forEach { it() }
}
