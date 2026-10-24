package com.storage.redirect.x.ui.page.pathbrowser

import android.annotation.SuppressLint
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.repository.PathBrowserRepository

internal enum class BrowserDialog { Settings, Location, Selection }

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
    var showHidden by mutableStateOf(true)
    var isDescending by mutableStateOf(false)
    var isExcludeMode by mutableStateOf(allowExclude && isInitiallyExcluded)
    var reloadKey by mutableIntStateOf(0)
    var dialog by mutableStateOf<BrowserDialog?>(null)
    private var loadGeneration = 0

    // 目标可能属于其他用户或应用私有挂载点，不能使用当前用户的存储目录。
    @get:SuppressLint("SdCardPath")
    val rootPath: String
        get() = if (packageName == null) "/storage/emulated/$userId"
        else "/storage/emulated/$userId/Android/data/$packageName/sdcard"

    val absolutePath: String
        get() = if (currentPath.isEmpty()) rootPath else "$rootPath/$currentPath"

    val visibleDirectories: List<String>
        get() = directories.filter { path ->
            val name = path.substringAfterLast('/')
            (showHidden || !name.startsWith('.')) && name.contains(query.trim(), ignoreCase = true)
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
