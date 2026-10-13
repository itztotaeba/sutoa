package com.kinyo.sutoa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinyo.sutoa.domain.model.AppItem
import com.kinyo.sutoa.domain.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val apps: List<AppItem> = emptyList(),
    val fromCache: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _snackbar = Channel<String>(Channel.BUFFERED)
    val snackbar = _snackbar.receiveAsFlow()

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val apps = if (forceRefresh) {
                repository.refreshFeatured()
            } else {
                repository.featured()
            }
            if (apps.isEmpty()) {
                _state.value = _state.value.copy(loading = false)
                _snackbar.send("Gagal memuat, cek koneksi")
            } else {
                _state.value = HomeUiState(loading = false, apps = apps, fromCache = false)
            }
        }
    }


}
