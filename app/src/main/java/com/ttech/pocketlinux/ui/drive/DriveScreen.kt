package com.ttech.pocketlinux.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun DriveScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Text("Google ドライブ（作成中）")
}
