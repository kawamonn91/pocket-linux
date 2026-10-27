package com.ttech.pocketlinux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 時間のかかる処理（コンテナの作成・バックアップ・復元・初期設定）の進み具合。
 * 動いている間は常駐サービスを止めない。
 */
class TaskTracker {

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: Int,
        val title: String,
        val label: String = "",
        val fraction: Float? = null,
        val log: List<String> = emptyList(),
        val state: State = State.RUNNING,
        val message: String? = null,
    )

    interface Context {
        fun progress(label: String, fraction: Float? = null)
        fun log(line: String)
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    private val jobs = mutableMapOf<Int, Job>()
    private var nextId = 1

    val runningCount: Int get() = _tasks.value.count { it.state == State.RUNNING }

    /** @param block 成功時の一言（null なら「完了しました」） */
    fun start(scope: CoroutineScope, title: String, block: suspend Context.() -> String?): Int {
        val id = synchronized(this) { nextId++ }
        _tasks.update { it + Task(id, title) }
        val context = object : Context {
            override fun progress(label: String, fraction: Float?) =
                change(id) { it.copy(label = label, fraction = fraction) }

            override fun log(line: String) =
                change(id) { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
        }
        jobs[id] = scope.launch {
            try {
                val message = block(context)
                change(id) { it.copy(state = State.DONE, label = "", fraction = 1f, message = message ?: "完了しました") }
            } catch (e: CancellationException) {
                change(id) { it.copy(state = State.CANCELLED, message = "中止しました") }
            } catch (e: Throwable) {
                change(id) { it.copy(state = State.FAILED, message = e.message ?: e.javaClass.simpleName) }
            } finally {
                jobs.remove(id)
            }
        }
        return id
    }

    fun cancel(id: Int) {
        jobs[id]?.cancel()
    }

    fun dismiss(id: Int) {
        _tasks.update { list -> list.filterNot { it.id == id && it.state != State.RUNNING } }
    }

    private fun change(id: Int, transform: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    companion object {
        private const val MAX_LOG_LINES = 400
    }
}
