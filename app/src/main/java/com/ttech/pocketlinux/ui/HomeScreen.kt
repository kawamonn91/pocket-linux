package com.ttech.pocketlinux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ttech.pocketlinux.TaskTracker
import com.ttech.pocketlinux.engine.ContainerConfig
import com.ttech.pocketlinux.engine.ImageRef
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** トップ画面の操作（実際の処理は MainActivity 側の HomeActions で行う） */
interface HomeActions {
    fun createContainer(image: String, name: String, installTools: Boolean)
    fun openShell(containerId: String, asRoot: Boolean)
    fun rerunSetup(containerId: String)
    fun rename(containerId: String, name: String)
    fun saveAutostart(containerId: String, command: String, enabled: Boolean)
    fun backup(container: ContainerConfig)
    fun restore()
    fun delete(containerId: String)
    fun cancelTask(id: Int)
    fun dismissTask(id: Int)
    fun openTerminals()
    fun openDrive()
    fun openSettings()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    containers: List<ContainerConfig>,
    tasks: List<TaskTracker.Task>,
    sessionCounts: Map<String, Int>,
    notices: List<Notice>,
    actions: HomeActions,
) {
    var showCreate by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pocket Linux") },
                actions = {
                    if (sessionCounts.values.sum() > 0) {
                        IconButton(onClick = actions::openTerminals) { Icon(Icons.Default.Terminal, "ターミナル") }
                    }
                    IconButton(onClick = actions::openDrive) { Icon(Icons.Default.Cloud, "Google ドライブ") }
                    IconButton(onClick = actions::openSettings) { Icon(Icons.Default.Settings, "設定") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "その他") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("バックアップから復元") }, onClick = { menuOpen = false; actions.restore() })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("コンテナを作成") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(notices, key = { "notice-" + it.id }) { NoticeCard(it) }
            items(tasks, key = { "task-" + it.id }) { task ->
                TaskCard(task, onCancel = { actions.cancelTask(task.id) }, onDismiss = { actions.dismissTask(task.id) })
            }
            if (containers.isEmpty() && tasks.none { it.state == TaskTracker.State.RUNNING }) {
                item { EmptyState(onCreate = { showCreate = true }) }
            }
            items(containers, key = { it.id }) { container ->
                ContainerCard(container, sessionCounts[container.id] ?: 0, actions)
            }
        }
    }

    if (showCreate) {
        CreateContainerDialog(
            onDismiss = { showCreate = false },
            onCreate = { image, name, tools ->
                showCreate = false
                actions.createContainer(image, name, tools)
            },
        )
    }
}

/** 画面上部に出すお知らせ（通知の許可・子プロセスの制限など） */
data class Notice(val id: String, val title: String, val body: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

@Composable
private fun NoticeCard(notice: Notice) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(notice.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(notice.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (notice.actionLabel != null && notice.onAction != null) {
                TextButton(onClick = notice.onAction) { Text(notice.actionLabel) }
            }
        }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("コンテナがまだありません", style = MaterialTheme.typography.titleMedium)
            Text(
                "Docker のイメージ（ubuntu:24.04 など）から、この端末の中に Linux の環境を作ります。" +
                    "root 化は不要で、中では sudo apt install が使えます。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Claude Code は `curl -fsSL https://claude.ai/install.sh | bash`、agy は " +
                    "`curl -fsSL https://antigravity.google/cli/install.sh | bash` で入ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onCreate) { Text("Ubuntu 24.04 で作成する") }
        }
    }
}

@Composable
private fun TaskCard(task: TaskTracker.Task, onCancel: () -> Unit, onDismiss: () -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            when (task.state) {
                TaskTracker.State.RUNNING -> {
                    Text(task.label.ifEmpty { "準備しています" }, style = MaterialTheme.typography.bodySmall)
                    if (task.fraction != null) {
                        LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                TaskTracker.State.FAILED -> Text(
                    "失敗しました: ${task.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(task.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            if (showLog && task.log.isNotEmpty()) {
                Text(
                    task.log.takeLast(60).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.log.isNotEmpty()) {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "ログを閉じる" else "ログを見る") }
                }
                Spacer(Modifier.weight(1f))
                if (task.state == TaskTracker.State.RUNNING) {
                    TextButton(onClick = onCancel) { Text("中止") }
                } else {
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerCard(container: ContainerConfig, sessions: Int, actions: HomeActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val created = remember(container.createdAt) {
        SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date(container.createdAt))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(container.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${container.image}・$created", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("root で開く") }, onClick = { menuOpen = false; actions.openShell(container.id, true) })
                        DropdownMenuItem(text = { Text("起動時の自動実行") }, onClick = { menuOpen = false; dialog = "autostart" })
                        DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menuOpen = false; dialog = "rename" })
                        DropdownMenuItem(text = { Text("バックアップ") }, onClick = { menuOpen = false; actions.backup(container) })
                        DropdownMenuItem(text = { Text("初期設定をやり直す") }, onClick = { menuOpen = false; actions.rerunSetup(container.id) })
                        DropdownMenuItem(text = { Text("削除", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; dialog = "delete" })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (container.user != null) {
                    AssistChip(onClick = {}, label = { Text("${container.user}・sudo 可") })
                } else {
                    AssistChip(onClick = {}, label = { Text("root でログイン") })
                }
                if (container.autostartEnabled && container.autostartCommand.isNotBlank()) {
                    AssistChip(onClick = { dialog = "autostart" }, label = { Text("自動実行あり") })
                }
                if (sessions > 0) AssistChip(onClick = actions::openTerminals, label = { Text("実行中 $sessions") })
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { actions.openShell(container.id, false) }) {
                    Icon(Icons.Default.Terminal, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("ターミナルを開く")
                }
            }
        }
    }

    when (dialog) {
        "rename" -> RenameDialog(container.name, onDismiss = { dialog = null }) { actions.rename(container.id, it); dialog = null }
        "autostart" -> AutostartDialog(container, onDismiss = { dialog = null }) { command, enabled ->
            actions.saveAutostart(container.id, command, enabled)
            dialog = null
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("「${container.name}」を削除しますか？") },
            text = { Text("コンテナの中のファイルはすべて消えます（~/drive の同期フォルダは消えません）。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { actions.delete(container.id); dialog = null }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名前を変更") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun AutostartDialog(container: ContainerConfig, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var command by remember { mutableStateOf(container.autostartCommand) }
    var enabled by remember { mutableStateOf(container.autostartEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("起動時の自動実行") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "このコンテナで最初のターミナルを開いたときに、裏のタブでこのコマンドを実行します" +
                        "（開発サーバーの起動など）。${container.user ?: "root"} として実行されます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = { Text("cd ~/drive/app && npm run dev") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("有効にする", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(command.trim(), enabled) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private val IMAGE_SUGGESTIONS = listOf(
    "ubuntu:24.04", "debian:trixie", "python:3.13-slim", "node:22-bookworm-slim", "alpine:3.22", "golang:1.25",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateContainerDialog(onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var image by remember { mutableStateOf("ubuntu:24.04") }
    var name by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf(true) }
    val error = remember(image) { runCatching { ImageRef.parse(image) }.exceptionOrNull()?.message }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("コンテナを作成") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = image,
                    onValueChange = { image = it.trim() },
                    label = { Text("イメージ") },
                    supportingText = { Text(error ?: "Docker Hub などの公開イメージ（arm64 版があるもの）") },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IMAGE_SUGGESTIONS.forEach { suggestion ->
                        SuggestionChip(onClick = { image = suggestion }, label = { Text(suggestion) })
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名前（省略可）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tools, onCheckedChange = { tools = it })
                    Text("よく使うツールも入れる（curl・git・nano・less）", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "一般ユーザー「user」を作り、パスワードなしで sudo を使えるようにします。" +
                        "数分かかり、通信量は 50〜300MB ほどです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(image, name.trim(), tools) }, enabled = error == null) { Text("作成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
