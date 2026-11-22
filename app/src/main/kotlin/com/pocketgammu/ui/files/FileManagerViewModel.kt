package com.pocketgammu.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgammu.AppContainer
import com.pocketgammu.core.gammu.model.ConnectionState
import com.pocketgammu.core.gammu.model.PhoneFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [id] null = root of the phone's file system. */
data class Crumb(val id: String?, val name: String)

data class Transfer(val name: String, val progress: Float, val upload: Boolean)

data class FilesUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val path: List<Crumb> = listOf(Crumb(null, "Phone")),
    val entries: List<PhoneFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val transfer: Transfer? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Connected
    val currentFolderId: String? get() = path.last().id
    val atRoot: Boolean get() = path.size == 1
}

class FileManagerViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.phone.state.distinctUntilChangedBy { it is ConnectionState.Connected }.collect { c ->
                _state.update { it.copy(connection = c, path = listOf(Crumb(null, "Phone")), entries = emptyList(), error = null) }
                if (c is ConnectionState.Connected) load()
            }
        }
        viewModelScope.launch {
            container.phone.state.collect { c -> _state.update { it.copy(connection = c) } }
        }
    }

    private fun load() {
        val folder = _state.value.currentFolderId
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            container.phone.listFolder(folder)
                .onSuccess { list -> _state.update { it.copy(entries = list, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't read the folder") } }
        }
    }

    fun refresh() = load()

    fun open(folder: PhoneFile) {
        if (!folder.isFolder) return
        _state.update { it.copy(path = it.path + Crumb(folder.id, folder.name)) }
        load()
    }

    fun up(): Boolean {
        if (_state.value.atRoot) return false
        _state.update { it.copy(path = it.path.dropLast(1)) }
        load()
        return true
    }

    fun goTo(index: Int) {
        if (index >= _state.value.path.lastIndex) return
        _state.update { it.copy(path = it.path.take(index + 1)) }
        load()
    }

    /** File chosen for download while the system save picker is open. */
    var pendingDownload: PhoneFile? = null

    fun takePendingDownload(): PhoneFile? = pendingDownload.also { pendingDownload = null }

    fun download(file: PhoneFile, sink: suspend (ByteArray) -> Unit, done: (String) -> Unit) {
        _state.update { it.copy(transfer = Transfer(file.name, 0f, upload = false)) }
        viewModelScope.launch {
            val result = container.phone.readFile(file.id) { p -> _state.update { it.copy(transfer = it.transfer?.copy(progress = p)) } }
            _state.update { it.copy(transfer = null) }
            result.onSuccess { bytes ->
                runCatching { sink(bytes) }.onSuccess { done("Saved ${file.name}") }.onFailure { done("Couldn't save ${file.name}") }
            }.onFailure { done(it.message ?: "Download failed") }
        }
    }

    fun upload(name: String, bytes: ByteArray, done: (String) -> Unit) {
        val folder = _state.value.currentFolderId ?: run { done("Open a folder on the phone first"); return }
        _state.update { it.copy(transfer = Transfer(name, 0f, upload = true)) }
        viewModelScope.launch {
            val result = container.phone.writeFile(folder, name, bytes) { p -> _state.update { it.copy(transfer = it.transfer?.copy(progress = p)) } }
            _state.update { it.copy(transfer = null) }
            result.onSuccess { done("Uploaded $name"); load() }.onFailure { done(it.message ?: "Upload failed") }
        }
    }

    fun delete(file: PhoneFile, done: (String) -> Unit) {
        viewModelScope.launch {
            container.phone.delete(file)
                .onSuccess { done("Deleted ${file.name}"); load() }
                .onFailure { done(it.message ?: "Couldn't delete ${file.name}") }
        }
    }

    fun createFolder(name: String, done: (String) -> Unit) {
        val parent = _state.value.currentFolderId ?: run { done("Open a drive first"); return }
        viewModelScope.launch {
            container.phone.createFolder(parent, name)
                .onSuccess { done("Created $name"); load() }
                .onFailure { done(it.message ?: "Couldn't create folder") }
        }
    }
}
