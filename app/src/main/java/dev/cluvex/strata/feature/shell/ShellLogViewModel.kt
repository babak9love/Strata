package dev.cluvex.strata.feature.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.LogEntry
import dev.cluvex.strata.core.shell.ShellLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShellLogUiState(val entries: List<LogEntry> = emptyList(), val loading: Boolean = true)

class ShellLogViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ShellLogStore(app)
    private val _state = MutableStateFlow(ShellLogUiState())
    val state: StateFlow<ShellLogUiState> = _state.asStateFlow()

    fun reload() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            _state.value = ShellLogUiState(list, loading = false)
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            _state.value = ShellLogUiState(emptyList(), loading = false)
        }
    }
}
