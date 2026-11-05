package com.gridimpose

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream

sealed interface Status {
    data object Idle : Status
    data class Working(val done: Int, val total: Int) : Status
    data class Done(val uri: Uri, val name: String, val note: String?) : Status
    data class Failed(val message: String) : Status
}

data class UiState(
    val inputUri: Uri? = null,
    val inputName: String? = null,
    val style: RuleStyle = RuleStyle.GRID,
    val spacingText: String = formatSpacing(RuleStyle.GRID.defaultSpacingMm),
    val removeDuplicates: Boolean = false,
    val status: Status = Status.Idle,
) {
    /** Parsed spacing, or null if the text isn't a number in the allowed range. */
    val spacingMm: Float?
        get() = spacingText.trim().replace(',', '.').toFloatOrNull()
            ?.takeIf { it in PdfProcessor.MIN_SPACING_MM..PdfProcessor.MAX_SPACING_MM }

    /** Dedupe-only mode always removes duplicates, whatever the checkbox says. */
    val dedupeEffective: Boolean
        get() = removeDuplicates || style == RuleStyle.DEDUPE_ONLY

    /** The spacing box is irrelevant (and hidden) in dedupe-only mode. */
    val needsSpacing: Boolean
        get() = style != RuleStyle.DEDUPE_ONLY

    val canCreate: Boolean
        get() = inputUri != null && (!needsSpacing || spacingMm != null) && status !is Status.Working
}

private fun formatSpacing(mm: Float): String =
    if (mm % 1f == 0f) mm.toInt().toString() else mm.toString()

/** A failure on the output side (disk full, storage provider error), not an unreadable PDF. */
private class OutputWriteException(cause: IOException) : IOException(cause.message, cause)

/** Passes everything through to [inner], re-labelling any IOException as an [OutputWriteException]. */
private class WriteGuard(private val inner: OutputStream) : OutputStream() {
    override fun write(b: Int) = guard { inner.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = guard { inner.write(b, off, len) }
    override fun flush() = guard { inner.flush() }
    override fun close() = guard { inner.close() }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: OutputWriteException) {
            throw e
        } catch (e: IOException) {
            throw OutputWriteException(e)
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val resolver get() = getApplication<Application>().contentResolver

    fun setInput(uri: Uri) {
        val name = displayName(uri) ?: "document.pdf"
        // A file shared into the app while a conversion runs must not wipe its progress state
        // (that would also re-enable "Create PDF" and allow a second concurrent run).
        _state.update {
            it.copy(inputUri = uri, inputName = name, status = idleUnlessWorking(it.status))
        }
    }

    fun setStyle(style: RuleStyle) {
        _state.update {
            it.copy(
                style = style,
                spacingText = formatSpacing(style.defaultSpacingMm),
                status = idleUnlessWorking(it.status),
            )
        }
    }

    fun setRemoveDuplicates(enabled: Boolean) {
        _state.update { it.copy(removeDuplicates = enabled, status = idleUnlessWorking(it.status)) }
    }

    fun setSpacingText(text: String) {
        _state.update { it.copy(spacingText = text, status = idleUnlessWorking(it.status)) }
    }

    fun suggestedOutputName(): String {
        val s = _state.value
        val name = s.inputName ?: "document"
        val base = if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name
        val suffix = when (s.style) {
            RuleStyle.GRID -> "grid"
            RuleStyle.LINES -> "lined"
            RuleStyle.DEDUPE_ONLY -> "unique"
        }
        return "${base}_$suffix.pdf"
    }

    /** Runs the conversion, writing straight into the document the user chose in the save dialog. */
    fun run(outUri: Uri) {
        val s = _state.value
        val input = s.inputUri ?: return
        val spacing = s.spacingMm ?: if (s.needsSpacing) return else s.style.defaultSpacingMm
        if (s.status is Status.Working) return

        _state.update { it.copy(status = Status.Working(0, 0)) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inStream = resolver.openInputStream(input)
                    ?: throw IOException("Could not open the selected file.")

                // The output is opened inside use{}, so the input is closed even if that fails.
                val result = inStream.use { i ->
                    val outStream = resolver.openOutputStream(outUri, "wt")
                        ?: throw OutputWriteException(IOException("Could not create the output file."))
                    WriteGuard(outStream.buffered()).use { o ->
                        PdfProcessor.process(
                            getApplication(), i, o, s.style, spacing, s.dedupeEffective,
                        ) { done, total ->
                            _state.update { it.copy(status = Status.Working(done, total)) }
                        }
                    }
                }
                val note = when {
                    !s.dedupeEffective -> null
                    !result.hadLabels -> "This PDF has no page labels, so no pages were removed."
                    else -> "Kept ${result.pagesKept} of ${result.pagesIn} pages " +
                        "(${result.pagesIn - result.pagesKept} duplicates removed)."
                }
                val name = displayName(outUri) ?: "output.pdf"
                _state.update { it.copy(status = Status.Done(outUri, name, note)) }
            } catch (e: Throwable) {
                // Don't leave an empty file behind in the user's storage.
                try {
                    DocumentsContract.deleteDocument(resolver, outUri)
                } catch (_: Exception) {
                }
                _state.update { it.copy(status = Status.Failed(messageFor(e))) }
            }
        }
    }

    private fun messageFor(e: Throwable): String {
        // PDFBox may wrap the original exception, so look through the cause chain.
        val causes = generateSequence(e) { it.cause }.take(8).toList()
        return when {
            causes.any { it is OutputWriteException } ->
                "Couldn't save the output file. Check that there is enough free space and try again."
            causes.any { it is InvalidPasswordException } ->
                "This PDF is password-protected. Remove the password and try again."
            e is OutOfMemoryError ->
                "This PDF is too large for the memory available on this device."
            e is IOException ->
                e.message?.takeIf { it.startsWith("Could not") }
                    ?: "Couldn't read that file as a PDF."
            else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun idleUnlessWorking(status: Status): Status =
        if (status is Status.Working) status else Status.Idle

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
