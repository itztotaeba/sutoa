package com.kinyo.sutoa.ui.updates

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinyo.sutoa.domain.model.AppItem
import com.kinyo.sutoa.domain.repository.AppRepository
import com.kinyo.sutoa.util.PackageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdatesUiState(
    val loading: Boolean = true,
    val updates: List<AppItem> = emptyList(),
    val installedUpToDate: Int = 0,
)

/**
 * Updates screen: compares known catalog (Room cache) against installed apps.
 * Numeric versionCode comparison only — never versionName strings.
 */
@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val application: Application,
    private val repository: AppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdatesUiState())
    val state: StateFlow<UpdatesUiState> = _state.asStateFlow()

    private val _snackbar = Channel<String>(Channel.BUFFERED)
    val snackbar = _snackbar.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            compute()
        }
    }

    /** Force a remote refresh of the whole catalog first, then recompute. */
    fun checkNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            if (repository.featured().isEmpty()) _snackbar.send("Gagal memuat, cek koneksi")
            // Refresh every app we know about individually too (best effort).
            repository.cachedApps().forEach { item ->
                runCatching { repository.refresh(item.packageName) }
            }
            compute()
        }
    }

    private suspend fun compute() {
        val catalog = repository.cachedApps()
        var upToDate = 0
        val updates = catalog.filter { item ->
            val installed = PackageUtils.installedVersionCode(application, item.packageName)
            when {
                installed == null -> false
                item.versionCode > installed -> true
                else -> { upToDate++; false }
            }
        }.sortedByDescending { it.lastUpdated }

        _state.value = UpdatesUiState(
            loading = false,
            updates = updates,
            installedUpToDate = upToDate,
        )
    }
}
