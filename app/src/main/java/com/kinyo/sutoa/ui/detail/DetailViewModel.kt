package com.kinyo.sutoa.ui.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kinyo.sutoa.domain.model.AppItem
import com.kinyo.sutoa.domain.repository.AppRepository
import com.kinyo.sutoa.domain.repository.RepoResult
import com.kinyo.sutoa.util.ApkInstaller
import com.kinyo.sutoa.util.PackageUtils
import com.kinyo.sutoa.util.canRequestPackageInstalls
import com.kinyo.sutoa.util.manageUnknownSourcesIntent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Install button state machine. */
enum class InstallAction { INSTALL, UPDATE, OPEN, DOWNLOADING }

data class DetailUiState(
    val loading: Boolean = true,
    val item: AppItem? = null,
    val installedVersionCode: Long? = null,
    val action: InstallAction = InstallAction.INSTALL,
    val progress: Float? = null, // null = indeterminate / not downloading
    val needsInstallPermission: Boolean = false,
) {
    val hasUpdate: Boolean
        get() = item != null && installedVersionCode != null && item.versionCode > installedVersionCode
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val application: Application,
    private val repository: AppRepository,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _snackbar = Channel<String>(Channel.BUFFERED)
    val snackbar = _snackbar.receiveAsFlow()

    /** Intents the screen should launch (install sheet / unknown-sources settings). */
    private val _launch = Channel<android.content.Intent>(Channel.BUFFERED)
    val launch = _launch.receiveAsFlow()

    fun load(packageName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            when (val result = repository.get(packageName)) {
                is RepoResult.Success -> applyItem(result.data)
                is RepoResult.Stale -> {
                    applyItem(result.data)
                    _snackbar.send("Data offline, menampilkan cache")
                }
                RepoResult.NetworkError -> {
                    _state.value = _state.value.copy(loading = false, item = null)
                    _snackbar.send("Gagal memuat, cek koneksi")
                }
            }
        }
    }

    fun refresh(packageName: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            when (val result = repository.refresh(packageName)) {
                is RepoResult.Success -> applyItem(result.data)
                is RepoResult.Stale -> applyItem(result.data)
                RepoResult.NetworkError -> {
                    _state.value = _state.value.copy(loading = false)
                    _snackbar.send("Gagal memuat, cek koneksi")
                }
            }
        }
    }

    /** Main CTA: install/update downloads+verifies then fires the ACTION_VIEW intent. */
    fun onPrimaryAction() {
        val item = _state.value.item ?: return
        when (_state.value.action) {
            InstallAction.OPEN -> {
                PackageUtils.getLaunchIntent(application, item.packageName)?.let {
                    runCatching { application.startActivity(it) }
                }
            }
            InstallAction.INSTALL, InstallAction.UPDATE -> startInstall(item)
            InstallAction.DOWNLOADING -> Unit // no-op while busy
        }
    }

    private fun startInstall(item: AppItem) = viewModelScope.launch {
        if (!canRequestPackageInstalls(application)) {
            _state.value = _state.value.copy(needsInstallPermission = true)
            _snackbar.send("Izinkan instalasi aplikasi unknown terlebih dahulu")
            _launch.send(manageUnknownSourcesIntent(application))
            return@launch
        }
        _state.value = _state.value.copy(action = InstallAction.DOWNLOADING, progress = 0f)
        try {
            val file = installer.prepare(item) { fraction ->
                viewModelScope.launch {
                    _state.value = _state.value.copy(progress = if (fraction >= 0f) fraction else null)
                }
            }
            _state.value = _state.value.copy(progress = null)
            _launch.send(installer.installIntent(file))
            _state.value = _state.value.copy(action = actionFor(item))
        } catch (t: Throwable) {
            _state.value = _state.value.copy(progress = null, action = actionFor(item))
            // Messages already user-friendly from InstallException subtypes.
            _snackbar.send(t.message ?: "File rusak, coba lagi")
        }
    }

    fun grantSettingsOpened() {
        _state.value = _state.value.copy(needsInstallPermission = false)
    }

    private fun applyItem(item: AppItem) {
        val installed = PackageUtils.installedVersionCode(application, item.packageName)
        _state.value = DetailUiState(
            loading = false,
            item = item,
            installedVersionCode = installed,
            action = actionFor(item),
        )
    }

    private fun actionFor(item: AppItem): InstallAction {
        val installed = PackageUtils.installedVersionCode(application, item.packageName)
        return when {
            installed == null -> InstallAction.INSTALL
            item.versionCode > installed -> InstallAction.UPDATE
            else -> InstallAction.OPEN
        }
    }
}
