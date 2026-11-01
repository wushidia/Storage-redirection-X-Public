package com.storage.redirect.x.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.model.MonitorAggregation
import com.storage.redirect.x.data.repository.FileMonitorRepository
import com.storage.redirect.x.data.repository.ConfigRepository
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class FileMonitorUiState(
    val isLoading: Boolean = true,
    val entries: ImmutableList<FileMonitorEntry> = persistentListOf(),
    val searchQuery: String = "",
    val isMonitoringEnabled: Boolean = true,
    val isChangingMonitoring: Boolean = false,
    val hasControlError: Boolean = false,
    val opTypeIndex: Int = 0,
    val isOpTypeMenuVisible: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val lostRecords: Long = 0,
    val writeErrors: Long = 0,
    val isLegacy: Boolean = false,
    val hasLoadError: Boolean = false,
    val daemonStale: Boolean = false,
)

class FileMonitorViewModel(
    private val monitorRepo: FileMonitorRepository = FileMonitorRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(FileMonitorUiState())
    val uiState: StateFlow<FileMonitorUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var before = 0L

    init {
        refresh()
        viewModelScope.launch {
            ConfigRepository.monitorEnabled.filterNotNull().collect { enabled ->
                _uiState.update { it.copy(isMonitoringEnabled = enabled) }
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isLoadingMore = false) }
            val page = monitorRepo.loadPage()
            if (page == null) {
                _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
                return@launch
            }
            ConfigRepository.publishMonitorEnabled(page.isMonitoringEnabled)
            val grouped =
                withContext(Dispatchers.Default) {
                    MonitorAggregation.group(page.entries).toImmutableList()
                }
            before = page.entries.lastOrNull()?.sequence ?: 0L
            _uiState.update {
                it.copy(
                    entries = grouped,
                    isLoading = false,
                    hasMore = page.hasMore,
                    lostRecords = page.lost,
                    writeErrors = page.writeErrors,
                    isLegacy = page.legacy,
                    hasLoadError = false,
                    daemonStale =
                        !page.legacy &&
                            (page.daemonUpdatedMs == 0L ||
                                System.currentTimeMillis() - page.daemonUpdatedMs > 10000),
                )
            }
        }
    }

    fun loadMore() {
        if (!_uiState.value.hasMore || _uiState.value.isLoading || _uiState.value.isLoadingMore)
            return
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val page = monitorRepo.loadPage(before = before)
            if (page != null) {
                val combined = _uiState.value.entries + page.entries
                val grouped =
                    withContext(Dispatchers.Default) {
                        MonitorAggregation.group(combined).toImmutableList()
                    }
                before = page.entries.lastOrNull()?.sequence ?: before
                _uiState.update {
                    it.copy(
                        entries = grouped,
                        hasMore = page.hasMore,
                        isLoadingMore = false,
                        hasLoadError = false,
                    )
                }
            } else _uiState.update { it.copy(isLoadingMore = false, hasLoadError = true) }
        }
    }

    fun clear() {
        if (_uiState.value.isLoading || _uiState.value.isLoadingMore) return
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, hasControlError = false) }
            if (monitorRepo.clear()) {
                before = 0L
                _uiState.update { it.copy(entries = persistentListOf(), hasMore = false, isLoading = false) }
            } else {
                _uiState.update { it.copy(isLoading = false, hasControlError = true) }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        if (_uiState.value.isChangingMonitoring) return
        viewModelScope.launch {
            _uiState.update { it.copy(isChangingMonitoring = true, hasControlError = false) }
            val saved = monitorRepo.setMonitoringEnabled(enabled)
            if (saved) ConfigRepository.publishMonitorEnabled(enabled)
            _uiState.update { it.copy(isChangingMonitoring = false, hasControlError = !saved) }
        }
    }

    fun updateOpTypeIndex(index: Int) {
        _uiState.update { it.copy(opTypeIndex = index, isOpTypeMenuVisible = false) }
    }

    fun setOpTypeMenuVisible(isVisible: Boolean) {
        _uiState.update { it.copy(isOpTypeMenuVisible = isVisible) }
    }

    fun resetFilters() {
        _uiState.update { it.copy(searchQuery = "", opTypeIndex = 0) }
    }
}
