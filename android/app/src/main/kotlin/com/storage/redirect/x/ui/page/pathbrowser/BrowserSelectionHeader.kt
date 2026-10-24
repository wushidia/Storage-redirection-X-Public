package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.annotation.DrawableRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.SrxSmallTopAppBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BrowserSelectionHeader(
    title: String,
    state: PathBrowserState,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column {
        SrxSmallTopAppBar(
            title = if (state.multiSelect) "" else title,
            navigationIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrowserIconButton(onBack, Modifier.testTag("browser_back")) {
                        Icon(MiuixIcons.Back, stringResource(R.string.browser_back))
                    }
                    if (state.multiSelect) {
                        TextButton(
                            text = stringResource(R.string.browser_selected_count, state.selectedPaths.size),
                            onClick = { state.dialog = BrowserDialog.Selection },
                            modifier = Modifier.testTag("browser_selected_paths"),
                            minWidth = 0.dp,
                            minHeight = 35.dp,
                            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent,
                                textColor = MiuixTheme.colorScheme.onSurface),
                        )
                    }
                }
            },
            actions = {
                BrowserSelectionActions(state, onConfirm)
            },
        )
        if (state.allowExclude) {
            val labels = listOf(stringResource(R.string.browser_allow_access), stringResource(R.string.browser_exclude_directory))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)) {
                val width = (maxWidth - 15.dp) / 2
                TabRowWithContour(tabs = labels, selectedTabIndex = if (state.isExcludeMode) 1 else 0,
                    onTabSelected = { state.isExcludeMode = it == 1 }, minWidth = width, maxWidth = width,
                    modifier = Modifier.testTag("browser_rule_mode"))
            }
        }
    }
}

@Composable
private fun BrowserSelectionActions(state: PathBrowserState, onConfirm: () -> Unit) {
    BrowserSortMenu(state)
    BrowserHeaderAction(R.drawable.browser_new_folder, stringResource(R.string.browser_new_folder),
        modifier = Modifier.testTag("browser_new_folder"), enabled = !state.isLoading && !state.hasLoadError,
        onClick = { state.dialog = BrowserDialog.CreateDirectory })
    if (state.multiSelect) {
        BrowserHeaderAction(R.drawable.browser_select_all, stringResource(R.string.apps_select_all),
            modifier = Modifier.testTag("browser_select_all"),
            enabled = !state.isLoading && state.visibleDirectories.any(state::canSelect),
            onClick = state::toggleVisibleSelection)
    }
    Box(Modifier.testTag("browser_confirm")) {
        BrowserHeaderAction(R.drawable.browser_confirm, stringResource(R.string.common_ok),
            modifier = Modifier.testTag("directory_confirm"), enabled = state.canConfirm,
            onClick = onConfirm)
    }
}

@Composable
private fun BrowserSortMenu(state: PathBrowserState) {
    val labels = listOf(stringResource(R.string.browser_sort_ascending), stringResource(R.string.browser_sort_descending))
    val entry = remember(labels, state.isDescending) {
        DropdownEntry(labels.mapIndexed { index, label ->
            DropdownItem(text = label, selected = state.isDescending == (index == 1),
                onClick = { state.isDescending = index == 1 })
        })
    }
    OverlayIconDropdownMenu(entry = entry, minWidth = 35.dp, minHeight = 35.dp,
        backgroundColor = Color.Transparent, modifier = Modifier.padding(horizontal = 4.dp).testTag("browser_sort")) {
        Icon(painterResource(R.drawable.browser_sort), stringResource(R.string.browser_sort), Modifier.size(24.dp))
    }
}

@Composable
private fun BrowserHeaderAction(
    @DrawableRes icon: Int, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.padding(horizontal = 4.dp), minWidth = 35.dp, minHeight = 35.dp,
        backgroundColor = Color.Transparent) {
        Icon(painterResource(icon), label, Modifier.size(24.dp), tint = if (enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
    }
}
