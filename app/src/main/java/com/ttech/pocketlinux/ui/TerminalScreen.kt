package com.ttech.pocketlinux.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import com.ttech.pocketlinux.terminal.TermSession
import com.ttech.pocketlinux.terminal.TerminalController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    sessions: List<TermSession>,
    @Suppress("UNUSED_PARAMETER") revision: Int,
    currentId: Int?,
    fontSizeSp: Int,
    controller: TerminalController,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNewTab: (asRoot: Boolean) -> Unit,
    onPaste: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val current = sessions.firstOrNull { it.id == currentId } ?: sessions.lastOrNull()
    var showInputBar by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(current?.id) {
        if (current == null) onBack() else if (current.id != currentId) onSelect(current.id)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // タブ
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "コンテナ一覧") }
                val selectedIndex = sessions.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
                if (sessions.isNotEmpty()) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        modifier = Modifier.weight(1f),
                        edgePadding = 0.dp,
                        containerColor = Color.Transparent,
                    ) {
                        sessions.forEachIndexed { index, session ->
                            Tab(
                                selected = index == selectedIndex,
                                onClick = { onSelect(session.id) },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            session.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                        if (index == selectedIndex) {
                                            Icon(
                                                Icons.Default.Close, "タブを閉じる",
                                                modifier = Modifier.size(16.dp).pointerInput(session.id) {
                                                    awaitEachGesture {
                                                        awaitFirstDown()
                                                        if (waitForUpOrCancellation() != null) onClose(session.id)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    Box(Modifier.weight(1f))
                }
                IconButton(onClick = { onNewTab(false) }) { Icon(Icons.Default.Add, "新しいタブ") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("root で新しいタブ") }, onClick = { menuOpen = false; onNewTab(true) })
                        DropdownMenuItem(
                            text = { Text(if (showInputBar) "文字入力欄を閉じる" else "文字入力欄（日本語）") },
                            onClick = { menuOpen = false; showInputBar = !showInputBar },
                        )
                        DropdownMenuItem(text = { Text("貼り付け") }, onClick = { menuOpen = false; onPaste() })
                    }
                }
            }
        }

        // 端末
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    TerminalView(context, null).apply {
                        setTerminalViewClient(controller)
                        applyFontSize(fontSizeSp)
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        controller.view = this
                    }
                },
                update = { view ->
                    view.applyFontSize(fontSizeSp)
                    current?.let { controller.attach(it.terminal) }
                },
                onRelease = { view -> if (controller.view === view) controller.view = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showInputBar) InputBar(controller)
        ExtraKeys(controller, onToggleInputBar = { showInputBar = !showInputBar })
    }

    LaunchedEffect(Unit) {
        delay(150)
        controller.showKeyboard()
    }
}

/** 日本語などを IME で確定してから送る欄（端末に直接打つと変換中の表示が乱れる IME 向け） */
@Composable
private fun InputBar(controller: TerminalController) {
    var text by remember { mutableStateOf("") }
    fun send(withEnter: Boolean) {
        controller.sendText(text + if (withEnter) "\r" else "")
        text = ""
    }
    Row(Modifier.fillMaxWidth().background(ExtraKeysBackground), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("ここで入力して送信") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send(withEnter = true) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ExtraKeysBackground,
                unfocusedContainerColor = ExtraKeysBackground,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
            modifier = Modifier.weight(1f).onFocusChanged { controller.inputBarFocused = it.isFocused },
        )
        IconButton(onClick = { send(withEnter = false) }) { Icon(Icons.AutoMirrored.Filled.Send, "送信", tint = Color.White) }
        IconButton(onClick = { send(withEnter = true) }) { Icon(Icons.AutoMirrored.Filled.KeyboardReturn, "送信して改行", tint = Color.White) }
    }
}

/** 画面下の補助キー（ソフトキーボードに無い ESC・CTRL・矢印など） */
@Composable
private fun ExtraKeys(controller: TerminalController, onToggleInputBar: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ExtraKeysBackground).padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            KeyButton("ESC") { controller.sendText("\u001b") }
            KeyButton("/") { controller.sendCodePoint('/'.code) }
            KeyButton("-") { controller.sendCodePoint('-'.code) }
            KeyButton("HOME") { controller.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
            KeyButton("↑", repeat = true) { controller.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            KeyButton("END") { controller.sendKey(KeyEvent.KEYCODE_MOVE_END) }
            KeyButton("PGUP") { controller.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
            KeyButton("あ") { onToggleInputBar() }
        }
        Row(Modifier.fillMaxWidth()) {
            KeyButton("TAB") { controller.sendText("\t") }
            KeyButton("CTRL", active = controller.ctrlDown) { controller.ctrlDown = !controller.ctrlDown }
            KeyButton("ALT", active = controller.altDown) { controller.altDown = !controller.altDown }
            KeyButton("←", repeat = true) { controller.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            KeyButton("↓", repeat = true) { controller.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            KeyButton("→", repeat = true) { controller.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            KeyButton("PGDN") { controller.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
            KeyButton("⌨") { controller.toggleKeyboard() }
        }
    }
}

/** 押している間は繰り返す（repeat = true のとき）キー */
@Composable
private fun RowScope.KeyButton(label: String, active: Boolean = false, repeat: Boolean = false, onPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent)
            .pointerInput(label, repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPress()
                    if (repeat) {
                        val job = scope.launch {
                            delay(400)
                            while (true) {
                                onPress()
                                delay(60)
                            }
                        }
                        waitForUpOrCancellation()
                        job.cancel()
                    } else {
                        waitForUpOrCancellation()
                    }
                }
            },
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 文字サイズ（sp）を端末ビューに反映する。setTypeface より先に呼ぶ
 * （TerminalView は文字サイズを決めたときに描画の準備をするため、先に書体を設定すると落ちる）。
 */
private fun TerminalView.applyFontSize(sp: Int) {
    setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics).toInt())
}
