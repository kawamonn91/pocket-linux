package com.ttech.pocketlinux.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttech.pocketlinux.AppSettings
import com.ttech.pocketlinux.engine.ContainerConfig
import com.ttech.pocketlinux.engine.ContainerManager
import com.ttech.pocketlinux.ide.IdeServer

/** 設定画面の操作（実際の処理は MainActivity 側で行う） */
interface SettingsActions {
    fun update(change: (AppSettings.Values) -> AppSettings.Values)
    fun regenerateIdeToken()
    fun copy(text: String)
    fun openInBrowser(url: String)
    fun requestStorageAccess()
    fun clearCache()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings.Values,
    containers: List<ContainerConfig>,
    ide: IdeServer.State,
    storageGranted: Boolean,
    cacheBytes: Long,
    addresses: List<Pair<String, String>>,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IdeCard(settings, containers, ide, addresses, actions) }
            item {
                Section("ターミナル") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("文字の大きさ: ${settings.fontSizeSp}", Modifier.weight(1f))
                        OutlinedButton(onClick = { actions.update { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceAtLeast(AppSettings.MIN_FONT)) } }) { Text("小さく") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { actions.update { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceAtMost(AppSettings.MAX_FONT)) } }) { Text("大きく") }
                    }
                    SwitchRow("ベルで振動する", settings.vibrateOnBell) { on -> actions.update { it.copy(vibrateOnBell = on) } }
                    SwitchRow(
                        "文字が二重に入るのを防ぐ",
                        settings.enforceCharInput,
                        note = "一部のキーボード（Samsung など）で、打った文字が二重に入るときにオンにします",
                    ) { on -> actions.update { it.copy(enforceCharInput = on) } }
                }
            }
            item {
                Section("動かし方") {
                    SwitchRow(
                        "スリープさせない",
                        settings.keepAwake,
                        note = "コンテナの処理を画面を消しても止めずに続けます（電池を多く使います）",
                    ) { on -> actions.update { it.copy(keepAwake = on) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("端末のストレージ（~/storage）")
                            Text(
                                if (storageGranted) "許可されています。コンテナの ~/storage から Download などを読み書きできます"
                                else "許可すると、コンテナの ~/storage から端末の Download などを読み書きできます",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!storageGranted) FilledTonalButton(onClick = actions::requestStorageAccess) { Text("許可する") }
                    }
                }
            }
            item {
                Section("データ") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("取得したイメージのキャッシュ: ${ContainerManager.formatBytes(cacheBytes)}", Modifier.weight(1f))
                        OutlinedButton(onClick = actions::clearCache, enabled = cacheBytes > 0) { Text("消す") }
                    }
                    Text(
                        "同じイメージでコンテナを作るときに、ダウンロードを省くためのものです。消しても作ったコンテナには影響しません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Section("このアプリについて") {
                    TextButton(onClick = { showLicenses = true }) { Text("オープンソースのライセンス") }
                }
            }
        }
    }

    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

@Composable
private fun IdeCard(
    settings: AppSettings.Values,
    containers: List<ContainerConfig>,
    ide: IdeServer.State,
    addresses: List<Pair<String, String>>,
    actions: SettingsActions,
) {
    val usable = containers.filter { it.setupDone }
    val selected = usable.firstOrNull { it.id == settings.ideContainerId } ?: usable.firstOrNull()
    var showLog by remember { mutableStateOf(false) }

    Section("IDE（pocket-dev）") {
        Text(
            "選んだコンテナの中で pocket-dev のサーバーを動かします。作業フォルダは ~/drive（Drive と同期するフォルダ）です。" +
                "pocket-dev アプリやブラウザから、編集とターミナルが使えます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("IDE のサーバーを動かす", settings.ideEnabled && selected != null, enabled = selected != null) { on ->
            actions.update { it.copy(ideEnabled = on, ideContainerId = selected?.id) }
        }
        if (selected == null) {
            Text("先にコンテナを作ってください", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            return@Section
        }
        ContainerPicker(usable, selected) { picked -> actions.update { it.copy(ideContainerId = picked.id) } }
        SwitchRow(
            "LAN・Tailscale から接続を許可する",
            settings.ideAllowLan,
            note = "オフのときは、この端末の中（pocket-dev アプリやブラウザ）からだけ接続できます。どちらもトークンが必要です",
        ) { on -> actions.update { it.copy(ideAllowLan = on) } }
        PortField(settings.idePort) { port -> actions.update { it.copy(idePort = port) } }

        val status = when {
            ide.running -> "動いています（${ide.containerName}）"
            ide.error != null -> "止まっています: ${ide.error}"
            else -> "止まっています"
        }
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (ide.error != null && !ide.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

        val local = "http://127.0.0.1:${settings.idePort}/ide.html?token=${settings.ideToken}"
        UrlRow("この端末から", local, actions)
        if (settings.ideAllowLan) {
            addresses.forEach { (name, ip) -> UrlRow("$name から", "http://$ip:${settings.idePort}/ide.html?token=${settings.ideToken}", actions) }
        }
        Row {
            TextButton(onClick = actions::regenerateIdeToken) { Text("トークンを作り直す") }
            if (ide.log.isNotEmpty()) TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "ログを閉じる" else "ログを見る") }
        }
        if (showLog) {
            Text(ide.log.takeLast(40).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ContainerPicker(containers: List<ContainerConfig>, selected: ContainerConfig, onPick: (ContainerConfig) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("コンテナ", Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) { Text(selected.name) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            containers.forEach { container ->
                DropdownMenuItem(text = { Text(container.name) }, onClick = { open = false; onPick(container) })
            }
        }
    }
}

@Composable
private fun PortField(port: Int, onChange: (Int) -> Unit) {
    var text by remember(port) { mutableStateOf(port.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1024..65535
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(5); it.toIntOrNull()?.takeIf { p -> p in 1024..65535 }?.let(onChange) },
        label = { Text("ポート") },
        singleLine = true,
        isError = !valid,
        supportingText = { if (!valid) Text("1024〜65535 で指定してください") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun UrlRow(label: String, url: String, actions: SettingsActions) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(url, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2)
        Row {
            TextButton(onClick = { actions.copy(url) }) { Text("コピー") }
            TextButton(onClick = { actions.openInBrowser(url) }) { Text("ブラウザで開く") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, note: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("オープンソースのライセンス") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(LICENSES, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** 同梱しているもの。ソースは各リポジトリで公開されている（GPL の proot は版も示す） */
private val LICENSES = """
    Pocket Linux には次のソフトウェアが含まれています。

    ■ proot 5.1.107-101（Termux のビルド）
    GNU General Public License v2.0
    ソース: https://github.com/termux/proot

    ■ talloc 2.5.0
    GNU Lesser General Public License v3.0
    ソース: https://www.samba.org/ftp/talloc/

    ■ libandroid-shmem 0.7
    BSD 3-Clause License
    ソース: https://github.com/termux/libandroid-shmem

    ■ Termux の terminal-emulator・terminal-view（Android Terminal Emulator 由来）
    Apache License 2.0
    ソース: https://github.com/termux/termux-app

    ■ rclone v1.75.2（必要な機能だけでビルド）
    MIT License
    ソース: https://github.com/rclone/rclone

    ■ pocket-dev（IDE のサーバーと画面）
    ソース: https://github.com/kawamonn91/pocket-dev

    ■ OkHttp・Apache Commons Compress・Kotlin・AndroidX・Jetpack Compose
    Apache License 2.0
""".trimIndent()
