package com.ttech.pocketlinux.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttech.pocketlinux.drive.DriveSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Drive 画面の操作（実際の処理は MainActivity 側で行う） */
interface DriveActions {
    fun login()
    fun logout()
    suspend fun listFolders(path: String): List<String>
    /** 失敗したときは利用者に見せる理由を返す */
    fun addPair(remotePath: String, localName: String): String?
    fun removePair(id: String, deleteLocal: Boolean)
    fun syncNow(id: String)
    fun resync(id: String)
    fun saveExcludes(text: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(state: DriveSync.State, actions: DriveActions, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var menuOpen by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showExcludes by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google ドライブ") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                actions = {
                    if (state.loggedIn) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "その他") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("同期しないもの") }, onClick = { menuOpen = false; showExcludes = true })
                            DropdownMenuItem(text = { Text("ログアウト") }, onClick = { menuOpen = false; actions.logout() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.loggedIn) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("フォルダを追加") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !state.available -> item { InfoCard("この端末向けの rclone が入っていないため、Drive と同期できません。") }
                !state.loggedIn -> item { LoginCard(state.loggingIn, state.loginError, actions::login) }
                else -> {
                    item {
                        Text(
                            "選んだ Drive のフォルダと、コンテナの ~/drive/<名前> を双方向に同期します。端末で変えたものは数秒後に、" +
                                "Drive 側で変えたものは数分おきとアプリを開いたときに取り込みます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.pairs.isEmpty()) item { InfoCard("同期するフォルダはまだありません。「フォルダを追加」から選んでください。") }
                    items(state.pairs, key = { it.pair.id }) { PairCard(it, actions) }
                }
            }
            if (state.log.isNotEmpty()) {
                item {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "ログを閉じる" else "ログを見る") }
                    if (showLog) {
                        Text(
                            state.log.takeLast(80).joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (showAdd) AddPairDialog(actions, existingNames = state.pairs.map { it.pair.localName }, onDismiss = { showAdd = false })
    if (showExcludes) ExcludesDialog(state.excludes, onDismiss = { showExcludes = false }) { actions.saveExcludes(it); showExcludes = false }
}

@Composable
private fun LoginCard(loggingIn: Boolean, error: String?, onLogin: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google ドライブにログイン", style = MaterialTheme.typography.titleMedium)
            Text(
                "ブラウザで Google の同意画面が開きます。同期には rclone（オープンソースの同期ツール）を使うため、同意画面には「rclone」と表示されます。",
                style = MaterialTheme.typography.bodyMedium,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ログイン中も押せる（ブラウザを閉じてしまったときなどに、やり直せるように）
                Button(onClick = onLogin) { Text(if (loggingIn) "やり直す" else "ログインする") }
                if (loggingIn) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("ブラウザで同意してください", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PairCard(item: DriveSync.PairState, actions: DriveActions) {
    val pair = item.pair
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("~/drive/${pair.localName}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("マイドライブ/${pair.remotePath}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "メニュー") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("最初からやり直す") }, onClick = { menuOpen = false; actions.resync(pair.id) })
                        DropdownMenuItem(text = { Text("同期をやめる") }, onClick = { menuOpen = false; confirmRemove = true })
                    }
                }
            }
            if (item.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            val status = when {
                item.running -> "同期しています…"
                pair.lastError != null -> "失敗: ${pair.lastError}"
                pair.lastSyncAt != null -> "最後に同期: " + SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(pair.lastSyncAt))
                else -> "まだ同期していません"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (pair.lastError != null && !item.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { actions.syncNow(pair.id) }, enabled = !item.running) { Text("今すぐ同期") }
            }
        }
    }
    if (confirmRemove) RemovePairDialog(pair.localName, onDismiss = { confirmRemove = false }) { deleteLocal ->
        actions.removePair(pair.id, deleteLocal)
        confirmRemove = false
    }
}

@Composable
private fun RemovePairDialog(name: String, onDismiss: () -> Unit, onRemove: (Boolean) -> Unit) {
    var deleteLocal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期をやめる") },
        text = {
            Column {
                Text("Drive 側のファイルはそのまま残ります。")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteLocal = !deleteLocal }) {
                    Checkbox(checked = deleteLocal, onCheckedChange = { deleteLocal = it })
                    Text("端末の ~/drive/$name も消す")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRemove(deleteLocal) }) { Text("やめる", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** Drive のフォルダをたどって選び、端末側の名前を決める */
@Composable
private fun AddPairDialog(actions: DriveActions, existingNames: List<String>, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(listOf<String>()) }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        folders = null
        loadError = null
        if (!nameEdited) name = path.lastOrNull().orEmpty()
        runCatching { actions.listFolders(path.joinToString("/")) }
            .onSuccess { folders = it }
            .onFailure { loadError = it.message ?: "フォルダの一覧を取得できません" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期するフォルダを選ぶ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (listOf("マイドライブ") + path).joinToString(" / "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (path.isNotEmpty()) FolderRow("..（ひとつ上へ）") { path = path.dropLast(1) }
                    when {
                        loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        folders == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        folders!!.isEmpty() -> Text("この中にフォルダはありません", style = MaterialTheme.typography.bodySmall)
                        else -> folders!!.forEach { folder -> FolderRow(folder) { path = path + folder } }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameEdited = true; addError = null },
                    label = { Text("端末側の名前（~/drive/<名前>）") },
                    singleLine = true,
                    isError = addError != null || name.trim() in existingNames,
                    supportingText = { addError?.let { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = path.isNotEmpty() && name.isNotBlank(),
                onClick = { addError = actions.addPair(path.joinToString("/"), name); if (addError == null) onDismiss() },
            ) { Text("このフォルダを同期") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun FolderRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExcludesDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期しないもの") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("変えると、すべてのフォルダを最初から合わせ直します。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
