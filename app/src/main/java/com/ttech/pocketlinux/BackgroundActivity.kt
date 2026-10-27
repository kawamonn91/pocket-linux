package com.ttech.pocketlinux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** ターミナル以外で常駐サービスを必要とするもの（Drive の同期・IDE のサーバー）の状態 */
class BackgroundActivity {

    data class State(val syncing: Boolean = false, val ideRunning: Boolean = false) {
        val anyRunning: Boolean get() = syncing || ideRunning
        val summary: String
            get() = listOfNotNull("Drive を同期中".takeIf { syncing }, "IDE のサーバー".takeIf { ideRunning }).joinToString("・")
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
