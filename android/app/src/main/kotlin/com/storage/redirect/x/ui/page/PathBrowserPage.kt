package com.storage.redirect.x.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.R
import com.storage.redirect.x.data.repository.PathBrowserRepository
import com.storage.redirect.x.ui.page.pathbrowser.BrowserBreadcrumbs
import com.storage.redirect.x.ui.page.pathbrowser.BrowserDirectoryList
import com.storage.redirect.x.ui.page.pathbrowser.BrowserScaffold
import com.storage.redirect.x.ui.page.pathbrowser.BrowserSearch
import com.storage.redirect.x.ui.page.pathbrowser.PathBrowserDialogs
import com.storage.redirect.x.ui.page.pathbrowser.PathBrowserState

@Composable
fun PathBrowserPage(
    title: String,
    browserRepository: PathBrowserRepository,
    userId: Int,
    packageName: String? = null,
    multiSelect: Boolean = false,
    allowExclude: Boolean = false,
    isInitiallyExcluded: Boolean = false,
    onBack: () -> Unit,
    onPathSelect: (String) -> Unit,
    onPathsSelect: (List<String>) -> Unit = {},
) {
    val state = remember(userId, packageName, multiSelect, allowExclude, isInitiallyExcluded) {
        PathBrowserState(userId, packageName, multiSelect, allowExclude, isInitiallyExcluded)
    }
    // 页面切换取消旧协程，状态代次同时拒绝尚未返回的旧请求。
    LaunchedEffect(state, state.currentPath, state.reloadKey) {
        state.load(browserRepository)
    }
    val navigateBack = {
        if (state.currentPath.isEmpty()) onBack()
        else state.navigateTo(state.currentPath.substringBeforeLast('/', ""))
    }
    BackHandler(enabled = state.dialog == null, onBack = navigateBack)

    BrowserScaffold(
        title = title,
        state = state,
        onBack = navigateBack,
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = BuildConfig.IS_DEBUG_BUILD }
            .testTag("page_path_browser"),
        onConfirm = {
            val paths = state.confirmedPaths()
            if (paths.isNotEmpty()) {
                if (multiSelect) onPathsSelect(paths) else onPathSelect(paths.first())
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BrowserBreadcrumbs(state)
            BrowserSearch(state.query, { state.query = it })
            BrowserDirectoryList(state, Modifier.weight(1f))
        }
    }
    PathBrowserDialogs(state, browserRepository)
}
