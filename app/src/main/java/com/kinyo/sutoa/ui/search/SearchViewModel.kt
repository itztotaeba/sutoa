package com.kinyo.sutoa.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinyo.sutoa.domain.model.AppItem
import com.kinyo.sutoa.domain.repository.AppRepository
import com.kinyo.sutoa.domain.repository.RepoResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val searched: Boolean = false,
    val results: List<AppItem> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _snackbar = Channel<String>(Channel.BUFFERED)
    val snackbar = _snackbar.receiveAsFlow()

    private var debounceJob: Job? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        debounceJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), searched = false, loading = false)
            return
        }
        debounceJob = viewModelScope.launch {
            delay(DEBOUNCE_MS) // 300 ms debounce
            searchNow(query)
        }
    }

    fun retry() {
        val q = _state.value.query
        if (q.isNotBlank()) searchNow(q)
    }

    private fun searchNow(query: String) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        when (val result = repository.search(query)) {
            is RepoResult.Success -> _state.value = _state.value.copy(
                loading = false,
                searched = true,
                results = result.data,
            )
            is RepoResult.Stale -> {
                _state.value = _state.value.copy(
                    loading = false,
                    searched = true,
                    results = result.data,
                )
                _snackbar.send("Data offline, menampilkan cache")
            }
            RepoResult.NetworkError -> {
                _state.value = _state.value.copy(loading = false, searched = true, results = emptyList())
                _snackbar.send("Gagal memuat, cek koneksi")
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
