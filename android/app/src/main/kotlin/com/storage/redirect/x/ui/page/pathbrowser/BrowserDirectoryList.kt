package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.CardItem
import com.storage.redirect.x.ui.component.SrxLazyColumn
import com.storage.redirect.x.ui.component.groupedCardItems
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun BrowserDirectoryList(state: PathBrowserState, modifier: Modifier = Modifier) {
    val entries = remember(state.directories, state.query, state.isDescending) { state.visibleDirectories }
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPath, state.query, state.isDescending) { listState.scrollToItem(0) }
    SrxLazyColumn(state = listState, modifier = modifier.testTag("browser_directories")) {
        when {
            state.isLoading -> item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.hasLoadError || entries.isEmpty() -> item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    BrowserEmptyState(state)
                }
            }
            else -> groupedCardItems("directories", entries.map { path ->
                CardItem(path) { BrowserDirectoryRow(state, path) }
            })
        }
    }
}

@Composable
private fun BrowserEmptyState(state: PathBrowserState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.browser_empty), null, Modifier.size(88.dp))
        Spacer(Modifier.height(12.dp))
        BrowserText(stringResource(when {
            state.hasLoadError -> R.string.rule_config_browser_load_failed
            state.query.isNotBlank() -> R.string.browser_no_results
            else -> R.string.rule_config_browser_empty
        }), color = if (state.hasLoadError) browserColors().error else browserColors().summary, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        if (state.hasLoadError) BrowserButton(stringResource(R.string.common_retry), { state.reloadKey++ })
        else if (state.query.isNotEmpty()) BrowserButton(stringResource(R.string.browser_clear_search), { state.query = "" })
    }
}

@Composable
private fun BrowserDirectoryRow(state: PathBrowserState, path: String) {
    val canSelect = state.canSelect(path)
    BasicComponent(
        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp).testTag("browser_directory_$path"),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        enabled = canSelect,
        onClick = { state.navigateTo(path) },
        startAction = {
            Image(painterResource(R.drawable.browser_folder), null,
                Modifier.padding(end = 16.dp).size(40.dp))
        },
        endActions = {
            if (state.multiSelect && canSelect) BrowserCheckbox(path in state.selectedPaths,
                stringResource(R.string.browser_select_directory, path.substringAfterLast('/')),
                { state.togglePath(path) }, Modifier.testTag("browser_select_$path"))
        },
    ) {
        BrowserText(path.substringAfterLast('/'), fontSize = 17.sp,
            fontWeight = FontWeight.Medium, maxLines = 2,
            color = if (canSelect) browserColors().text else browserColors().summary)
        if (!canSelect) {
            Spacer(Modifier.height(2.dp))
            BrowserText(stringResource(R.string.browser_restricted), fontSize = 12.sp,
                color = browserColors().summary)
        }
    }
}
