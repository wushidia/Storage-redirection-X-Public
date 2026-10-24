package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.repository.PathBrowserRepository

internal enum class BrowserDialog { CreateDirectory, Selection }

@Stable
internal class PathBrowserState(
    val userId: Int,
    val packageName: String?,
    val multiSelect: Boolean,
    val allowExclude: Boolean,
    isInitiallyExcluded: Boolean = false,
) {
    var currentPath by mutableStateOf("")
        private set
    var query by mutableStateOf("")
    var directories by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedPaths by mutableStateOf<Set<String>>(emptySet())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var hasLoadError by mutableStateOf(false)
        private set
    var isDescending by mutableStateOf(false)
    var isExcludeMode by mutableStateOf(allowExclude && isInitiallyExcluded)
    var reloadKey by mutableIntStateOf(0)
    var dialog by mutableStateOf<BrowserDialog?>(null)
    private var loadGeneration = 0

    val visibleDirectories: List<String>
        get() = directories.filter { path ->
            val name = path.substringAfterLast('/')
            name.contains(query.trim(), ignoreCase = true)
        }.let { if (isDescending) it.reversed() else it }

    val canConfirm: Boolean
        get() = if (multiSelect) selectedPaths.isNotEmpty()
        else !isLoading && !hasLoadError && canSelect(currentPath)

    fun canSelect(path: String): Boolean {
        if (path.isEmpty()) return false
        if (packageName != null) {
            return !path.startsWith('/') && !path.startsWith('!') &&
                path.split('/').none { it == "." || it == ".." } &&
                !path.contains('*') && !path.contains('?')
        }
        return RedirectConfig.validateMappingPath(path) is PathValidationResult.Valid
    }

    fun navigateTo(path: String) {
        if (path.isNotEmpty() && !canSelect(path)) return
        // 在新协程启动前失效旧请求，同一路径的往返也不能复用旧结果。
        loadGeneration += 1
        currentPath = path
        query = ""
        directories = emptyList()
        isLoading = true
        hasLoadError = false
        reloadKey++
    }

    fun togglePath(path: String) {
        if (!multiSelect || !canSelect(path)) return
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    fun toggleVisibleSelection() {
        if (!multiSelect) return
        val selectable = visibleDirectories.filter(::canSelect).toSet()
        selectedPaths = if (selectable.all { it in selectedPaths }) selectedPaths - selectable
        else selectedPaths + selectable
    }

    fun clearSelection() {
        selectedPaths = emptySet()
    }

    fun confirmedPaths(): List<String> {
        if (!canConfirm) return emptyList()
        val prefix = if (allowExclude && isExcludeMode) "!" else ""
        val paths = if (multiSelect) selectedPaths.toList() else listOf(currentPath)
        return paths.map { prefix + it }
    }

    suspend fun load(repository: PathBrowserRepository) {
        loadGeneration += 1
        val generation = loadGeneration
        val path = currentPath
        isLoading = true
        hasLoadError = false
        directories = emptyList()
        val result = if (packageName == null) {
            repository.listRelativeDirectories(userId, path)
        } else {
            repository.listAppPrivateDirectories(userId, packageName, path)
        }
        if (generation != loadGeneration) return
        directories = result.orEmpty()
        hasLoadError = result == null
        isLoading = false
    }
}
