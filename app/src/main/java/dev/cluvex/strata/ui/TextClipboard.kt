package dev.cluvex.strata.ui

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TextClipboard(private val clipboard: Clipboard, private val scope: CoroutineScope) {
    fun setText(text: String) {
        scope.launch { clipboard.setClipEntry(ClipData.newPlainText("", text).toClipEntry()) }
    }

    fun readText(onText: (String) -> Unit) {
        scope.launch {
            val data = clipboard.getClipEntry()?.clipData
            onText(if (data != null && data.itemCount > 0) data.getItemAt(0).text?.toString().orEmpty() else "")
        }
    }
}

@Composable
fun rememberTextClipboard(): TextClipboard {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { TextClipboard(clipboard, scope) }
}
