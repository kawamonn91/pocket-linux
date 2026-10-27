package com.ttech.pocketlinux.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Text("設定（作成中）")
}
