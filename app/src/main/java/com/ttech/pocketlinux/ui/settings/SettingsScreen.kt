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
import com.ttech.pocketlinux.vm.VmManager

/** 設定画面の操作（実際の処理は MainActivity 側で行う） */
interface SettingsActions {
    fun update(change: (AppSettings.Values) -> AppSettings.Values)
    fun regenerateIdeToken()
    fun copy(text: String)
    fun openInBrowser(url: String)
    fun requestStorageAccess()
    fun clearCache()
    fun stopVm()
    fun consoleLog(): String
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings.Values,
    containers: List<ContainerConfig>,
    ide: IdeServer.State,
    vm: VmManager.Status,
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
            item { VmCard(settings, vm, actions) }
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
                                if (storageGranted) "許可されています。コンテナの ~/storage から Download などを読み書きできます（許可した後に VM を起動したときから）"
                                else "許可すると、コンテナの ~/storage から端末の Download などを読み書きできます（次に VM を起動したときから）",
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
private fun VmCard(settings: AppSettings.Values, vm: VmManager.Status, actions: SettingsActions) {
    var log by remember { mutableStateOf<String?>(null) }
    Section("Linux の VM") {
        val status = when (vm.state) {
            VmManager.State.RUNNING -> "動いています（起動に %.1f 秒）".format(vm.bootMillis / 1000f)
            VmManager.State.STARTING -> "起動しています"
            VmManager.State.STOPPING -> "止めています"
            VmManager.State.STOPPED -> vm.error?.let { "止まっています: $it" } ?: "止まっています（使うときに起動します）"
        }
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (vm.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRow("メモリ", settings.vmMemoryMb, VM_MEMORY_CHOICES) { value -> actions.update { it.copy(vmMemoryMb = value) } }
        ChoiceRow("CPU の数", settings.vmCpus, VM_CPU_CHOICES) { value -> actions.update { it.copy(vmCpus = value) } }
        ChoiceRow(
            "ディスクの上限",
            settings.vmDiskGb,
            VM_DISK_CHOICES.filter { it >= settings.vmDiskGb }.map { it to "$it GB" },
        ) { value -> actions.update { it.copy(vmDiskGb = value) } }
        Text(
            "変更は次に VM を起動したときから効きます。ディスクは使った分だけ端末の容量を使い、上限は大きくできますが小さくはできません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = actions::stopVm, enabled = vm.state == VmManager.State.RUNNING) { Text("VM を止める") }
            TextButton(onClick = { log = actions.consoleLog() }) { Text("起動ログ") }
        }
    }
    log?.let { text ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text("起動ログ") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                    Text(text.ifEmpty { "ログはまだありません" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { actions.copy(text) }) { Text("コピー") } },
            dismissButton = { TextButton(onClick = { log = null }) { Text("閉じる") } },
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Int, choices: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) { Text(choices.firstOrNull { it.first == selected }?.second ?: "$selected") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

private val VM_MEMORY_CHOICES = listOf(0 to "自動", 512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB")
private val VM_CPU_CHOICES = listOf(0 to "自動", 1 to "1", 2 to "2", 4 to "4", 8 to "8")
private val VM_DISK_CHOICES = listOf(8, 16, 32, 64, 128, 256, 512)

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

/** 同梱しているもの。GPL のものは版とソースの置き場所を示す（docs/vm-plan.md の 8 章） */
private val LICENSES = """
    Pocket Linux には次のソフトウェアが含まれています。

    ■ QEMU 11.1.3
    GNU General Public License v2.0
    ソース・ビルド手順・変更点: https://github.com/kawamonn91/pocket-linux/releases （vm/scripts/build-qemu.sh）

    ■ Linux 6.18.60
    GNU General Public License v2.0
    ソース・設定: https://github.com/kawamonn91/pocket-linux/releases （vm/scripts/build-kernel.sh）

    ■ GLib 2.90・libiconv 1.19（QEMU に静的にリンク）
    GNU Lesser General Public License v2.1
    ソース: https://github.com/kawamonn91/pocket-linux/releases

    ■ libslirp 4.9・PCRE2 10.49・libffi 3.8・zlib 1.4（QEMU に静的にリンク）
    BSD 3-Clause・MIT・zlib License
    ソース: https://github.com/kawamonn91/pocket-linux/releases

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
