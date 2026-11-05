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

sealed interface Status {
    data object Idle : Status
    data class Working(val done: Int, val total: Int) : Status
    data class Done(val uri: Uri, val name: String) : Status
    data class Failed(val message: String) : Status
}

data class UiState(
    val inputUri: Uri? = null,
    val inputName: String? = null,
    val style: RuleStyle = RuleStyle.GRID,
    val spacingText: String = formatSpacing(RuleStyle.GRID.defaultSpacingMm),
    val status: Status = Status.Idle,
) {
    /** Parsed spacing, or null if the text isn't a number in the allowed range. */
    val spacingMm: Float?
        get() = spacingText.trim().replace(',', '.').toFloatOrNull()
            ?.takeIf { it in PdfProcessor.MIN_SPACING_MM..PdfProcessor.MAX_SPACING_MM }

    val canCreate: Boolean
        get() = inputUri != null && spacingMm != null && status !is Status.Working
}

private fun formatSpacing(mm: Float): String =
    if (mm % 1f == 0f) mm.toInt().toString() else mm.toString()

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val resolver get() = getApplication<Application>().contentResolver

    fun setInput(uri: Uri) {
        val name = displayName(uri) ?: "document.pdf"
        _state.update { it.copy(inputUri = uri, inputName = name, status = Status.Idle) }
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

    fun setSpacingText(text: String) {
        _state.update { it.copy(spacingText = text, status = idleUnlessWorking(it.status)) }
    }

    fun suggestedOutputName(): String {
        val s = _state.value
        val base = (s.inputName ?: "document").removeSuffix(".pdf").removeSuffix(".PDF")
        return "${base}_${if (s.style == RuleStyle.GRID) "grid" else "lined"}.pdf"
    }

    /** Runs the conversion, writing straight into the document the user chose in the save dialog. */
    fun run(outUri: Uri) {
        val s = _state.value
        val input = s.inputUri ?: return
        val spacing = s.spacingMm ?: return
        if (s.status is Status.Working) return

        _state.update { it.copy(status = Status.Working(0, 0)) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inStream = resolver.openInputStream(input)
                    ?: throw IOException("Could not open the selected file.")
                val outStream = resolver.openOutputStream(outUri, "wt")
                    ?: throw IOException("Could not create the output file.")

                inStream.use { i ->
                    outStream.buffered().use { o ->
                        PdfProcessor.process(getApplication(), i, o, s.style, spacing) { done, total ->
                            _state.update { it.copy(status = Status.Working(done, total)) }
                        }
                    }
                }
                val name = displayName(outUri) ?: "output.pdf"
                _state.update { it.copy(status = Status.Done(outUri, name)) }
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

    private fun messageFor(e: Throwable): String = when (e) {
        is InvalidPasswordException ->
            "This PDF is password-protected. Remove the password and try again."
        is OutOfMemoryError ->
            "This PDF is too large for the memory available on this device."
        is IOException ->
            e.message?.takeIf { it.startsWith("Could not") }
                ?: "Couldn't read that file as a PDF."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
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
