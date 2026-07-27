package sh.haven.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface EditorState {
    data object Idle : EditorState
    data object Loading : EditorState
    data class Loaded(
        val content: String,
        val fileName: String,
        val filePath: String,
        val charset: java.nio.charset.Charset = Charsets.UTF_8,
    ) : EditorState
    data class Error(val message: String) : EditorState
}

@HiltViewModel
class EditorViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow<EditorState>(EditorState.Idle)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _wordWrap = MutableStateFlow(true)
    val wordWrap: StateFlow<Boolean> = _wordWrap.asStateFlow()

    private var provider: FileContentProvider? = null

    fun loadFile(contentProvider: FileContentProvider) {
        provider = contentProvider
        _state.value = EditorState.Loading
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { contentProvider.readContent() }
                val charset = detectCharset(bytes)
                val text = String(bytes, charset)
                _state.value = EditorState.Loaded(
                    content = text,
                    fileName = contentProvider.fileName,
                    filePath = contentProvider.filePath,
                    charset = charset,
                )
            } catch (e: Exception) {
                _state.value = EditorState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun toggleWordWrap() {
        _wordWrap.value = !_wordWrap.value
    }

    fun close() {
        _state.value = EditorState.Idle
        provider = null
    }

    private fun detectCharset(bytes: ByteArray): java.nio.charset.Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8

        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        }

        return Charsets.UTF_8
    }
}
