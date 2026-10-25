package dev.cluvex.strata.feature.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel

data class EditorChrome(
    val background: Int,
    val gutter: Int,
    val lineNumber: Int,
    val lineNumberCurrent: Int,
    val divider: Int,
    val currentLine: Int,
    val selection: Int,
    val cursor: Int,
    val blockLine: Int,
    val blockLineCurrent: Int,
    val scrollThumb: Int,
    val scrollThumbPressed: Int,
    val popup: Int,
    val popupBorder: Int,
    val popupText: Int,
    val popupTextSecondary: Int,
    val popupCurrent: Int,
    val match: Int,
    val matchBorder: Int,
    val delimiter: Int,
    val delimiterBackground: Int,
    val nonPrintable: Int,
) {
    companion object {
        fun from(c: ColorScheme) = EditorChrome(
            background = c.surface.toArgb(),
            gutter = c.surfaceContainerLow.toArgb(),
            lineNumber = c.onSurfaceVariant.copy(alpha = 0.55f).toArgb(),
            lineNumberCurrent = c.primary.toArgb(),
            divider = c.outlineVariant.copy(alpha = 0.6f).toArgb(),
            currentLine = c.primary.copy(alpha = 0.06f).toArgb(),
            selection = c.primary.copy(alpha = 0.26f).toArgb(),
            cursor = c.primary.toArgb(),
            blockLine = c.outlineVariant.toArgb(),
            blockLineCurrent = c.primary.copy(alpha = 0.55f).toArgb(),
            scrollThumb = c.onSurfaceVariant.copy(alpha = 0.32f).toArgb(),
            scrollThumbPressed = c.primary.toArgb(),
            popup = c.surfaceContainerHigh.toArgb(),
            popupBorder = c.outlineVariant.toArgb(),
            popupText = c.onSurface.toArgb(),
            popupTextSecondary = c.onSurfaceVariant.toArgb(),
            popupCurrent = c.secondaryContainer.toArgb(),
            match = c.tertiaryContainer.copy(alpha = 0.85f).toArgb(),
            matchBorder = c.tertiary.toArgb(),
            delimiter = c.primary.toArgb(),
            delimiterBackground = c.primary.copy(alpha = 0.14f).toArgb(),
            nonPrintable = c.outline.toArgb(),
        )
    }
}

class StrataColorScheme(
    registry: ThemeRegistry,
    model: ThemeModel,
    chrome: EditorChrome?,
) : TextMateColorScheme(registry, model) {
    private var chromeColors: EditorChrome? = chrome

    init {
        applyChrome()
    }

    override fun applyDefault() {
        super.applyDefault()
        applyChrome()
    }

    private fun applyChrome() {
        val c = chromeColors ?: return
        setColor(WHOLE_BACKGROUND, c.background)
        setColor(LINE_NUMBER_BACKGROUND, c.gutter)
        setColor(LINE_NUMBER, c.lineNumber)
        setColor(LINE_NUMBER_CURRENT, c.lineNumberCurrent)
        setColor(LINE_DIVIDER, c.divider)
        setColor(CURRENT_LINE, c.currentLine)
        setColor(SELECTED_TEXT_BACKGROUND, c.selection)
        setColor(SELECTION_INSERT, c.cursor)
        setColor(SELECTION_HANDLE, c.cursor)
        setColor(BLOCK_LINE, c.blockLine)
        setColor(BLOCK_LINE_CURRENT, c.blockLineCurrent)
        setColor(SCROLL_BAR_THUMB, c.scrollThumb)
        setColor(SCROLL_BAR_THUMB_PRESSED, c.scrollThumbPressed)
        setColor(COMPLETION_WND_BACKGROUND, c.popup)
        setColor(COMPLETION_WND_CORNER, c.popupBorder)
        setColor(COMPLETION_WND_TEXT_PRIMARY, c.popupText)
        setColor(COMPLETION_WND_TEXT_SECONDARY, c.popupTextSecondary)
        setColor(COMPLETION_WND_ITEM_CURRENT, c.popupCurrent)
        setColor(COMPLETION_WND_TEXT_MATCHED, c.cursor)
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, c.popup)
        setColor(TEXT_ACTION_WINDOW_ICON_COLOR, c.popupText)
        setColor(MATCHED_TEXT_BACKGROUND, c.match)
        setColor(MATCHED_TEXT_BORDER, c.matchBorder)
        setColor(HIGHLIGHTED_DELIMITERS_FOREGROUND, c.delimiter)
        setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, c.delimiterBackground)
        setColor(STICKY_SCROLL_DIVIDER, c.divider)
        setColor(NON_PRINTABLE_CHAR, c.nonPrintable)
    }
}

fun autoThemeId(dark: Boolean) = if (dark) "dark-plus" else "light-plus"

fun isDarkBackground(color: Color): Boolean = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) < 0.5
