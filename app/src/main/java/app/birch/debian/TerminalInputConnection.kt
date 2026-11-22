package app.birch.debian

import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.TextSnapshot
import androidx.annotation.RequiresApi

/** IME commits are a terminal stream, not edits to WebView's hidden textarea. */
internal class TerminalInputConnection(target: InputConnection, private val send: (String) -> Unit) :
    InputConnectionWrapper(target, false) {
    private var composingText: String? = null
    private var closed = false

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = ""
    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
    override fun getSelectedText(flags: Int): CharSequence? = null

    // Do not expose WebView's unrelated editor state through alternate IME queries.
    override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? = null

    @RequiresApi(31)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = null

    @RequiresApi(33)
    override fun takeSnapshot(): TextSnapshot? = null

    @Synchronized
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null || text.length > Console.MAX_INPUT_LENGTH) return false
        composingText = null
        if (text.isNotEmpty()) send(text.toString())
        return true
    }

    // These API overloads must be overridden: the wrapper would otherwise pass
    // the edit directly to WebView instead of using the terminal's raw stream.
    @RequiresApi(33)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        commitText(text, newCursorPosition)

    @RequiresApi(34)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        commitText(text, newCursorPosition)

    @Synchronized
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null || text.length > Console.MAX_INPUT_LENGTH) return false
        composingText = text.toString()
        return true
    }

    @RequiresApi(33)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        setComposingText(text, newCursorPosition)

    override fun setComposingRegion(start: Int, end: Int): Boolean = false

    @RequiresApi(33)
    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean = false

    // newCursorPosition and selection belong to a text editor. Never synthesize
    // cursor movement or replacements in the running terminal from those hints.
    override fun setSelection(start: Int, end: Int): Boolean = false

    @Synchronized
    override fun finishComposingText(): Boolean {
        val pending = composingText ?: return !closed
        return commitText(pending, 1)
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = commitText(text?.text, 1)
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false

    @Synchronized
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (closed) return false
        // Bound the generated key sequence before allocating it. This is the
        // character limit of Console.input, not the size of an individual PTY frame.
        if (beforeLength < 0 || afterLength < 0 ||
            beforeLength.toLong() + 4L * afterLength > Console.MAX_INPUT_LENGTH) return false
        val keys = "\u007f".repeat(beforeLength) + "\u001b[3~".repeat(afterLength)
        if (keys.isNotEmpty()) send(keys)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    @Synchronized
    override fun closeConnection() {
        if (closed) return
        closed = true
        composingText = null
        super.closeConnection()
    }
}
