package dev.cluvex.strata.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

object Bidi {
    const val FIRST_STRONG_ISOLATE = '⁨'
    const val POP_ISOLATE = '⁩'

    fun isolate(text: String): String = "$FIRST_STRONG_ISOLATE$text$POP_ISOLATE"
}

@Composable
@ReadOnlyComposable
fun isolated(text: String): String =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) Bidi.isolate(text) else text

@Composable
@ReadOnlyComposable
fun TextStyle.asPath(): TextStyle {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return copy(textDirection = TextDirection.Ltr, textAlign = if (rtl) TextAlign.Right else TextAlign.Left)
}

@Composable
@ReadOnlyComposable
fun TextStyle.asName(): TextStyle = copy(textDirection = TextDirection.Content)
