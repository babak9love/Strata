package dev.cluvex.strata.feature.editor

import android.graphics.Typeface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.widget.CodeEditor

const val CODE_EDITOR_TAG = "strata-code-editor"

@Composable
fun CodeEditorView(controller: EditorController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            CodeEditor(context).apply {
                tag = CODE_EDITOR_TAG
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                isHighlightCurrentLine = true
                isCursorAnimationEnabled = true
                setScalable(true)
                props.stickyScroll = true
                props.symbolPairAutoCompletion = true
                props.autoIndent = true
                props.highlightMatchingDelimiters = true
                controller.attach(this)
            }
        },
        modifier = modifier,
        onRelease = { view ->
            controller.detach()
            view.release()
        },
    )
}
