package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.*
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.theme.StatusColors
import com.storage.redirect.x.ui.util.sheetHeightTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun FileMonitorPage(
    monitorViewModel: FileMonitorViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val state by monitorViewModel.uiState.collectAsStateWithLifecycle()
    var isClearDialogVisible by remember { mutableStateOf(false) }
    val names = remember { mutableStateMapOf<String, String>() }
    val icons = remember { mutableStateMapOf<String, Drawable?>() }
    LaunchedEffect(state.entries) {
        val packages = state.entries.map { it.actorIdentity }.distinct().filter { it !in names }
        val metadata =
            withContext(Dispatchers.IO) {
                packages.associateWith { pkg ->
                    runCatching {
                            context.packageManager.getApplicationInfo(pkg, 0).let {
                                it.loadLabel(context.packageManager).toString() to
                                    it.loadIcon(context.packageManager)
                            }
                        }
                        .getOrDefault(pkg to null)
                }
            }
        metadata.forEach { (pkg, meta) ->
            names[pkg] = meta.first
            icons[pkg] = meta.second
        }
    }
    val filters =
        listOf(
            stringResource(R.string.monitor_all),
            stringResource(R.string.file_monitor_action_open),
            stringResource(R.string.file_monitor_action_create),
            stringResource(R.string.file_monitor_action_delete),
            stringResource(R.string.file_monitor_action_rename),
            stringResource(R.string.file_monitor_action_read),
            stringResource(R.string.file_monitor_action_write),
            stringResource(R.string.file_monitor_action_truncate),
            stringResource(R.string.file_monitor_action_map),
            stringResource(R.string.file_monitor_action_sync),
        )
    val selectFilter by rememberUpdatedState(monitorViewModel::updateOpTypeIndex)
    val filterEntry =
        remember(filters, state.opTypeIndex) {
            DropdownEntry(
                filters.mapIndexed { index, label ->
                    DropdownItem(
                        text = label,
                        selected = index == state.opTypeIndex,
                        onClick = { selectFilter(index) },
                    )
                }
            )
        }
    val filtered =
        remember(state.entries, state.searchQuery, state.opTypeIndex, names.toMap()) {
            state.entries.filter { entry ->
                (state.opTypeIndex == 0 ||
                    entry.actionType == MonitorActionType.entries[state.opTypeIndex - 1]) &&
                    (state.searchQuery.isBlank() ||
                        listOf(
                                entry.path,
                                entry.packageName,
                                entry.subjectPackage,
                                entry.actorIdentity,
                                names[entry.actorIdentity].orEmpty(),
                            )
                            .any { it.contains(state.searchQuery, true) })
            }
        }
    SearchScreenScaffold(
        title = stringResource(R.string.nav_monitor),
        label = stringResource(R.string.file_monitor_search_hint),
        query = state.searchQuery,
        onQueryChange = monitorViewModel::updateSearchQuery,
        isResultEmpty = filtered.isEmpty(),
        bottomPadding = bottomInnerPadding,
        modifier = Modifier.testTag("page_monitor"),
        isRefreshing = state.isLoading,
        onRefresh = monitorViewModel::refresh,
        emptyResult = {
            SrxLazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = bottomInnerPadding)) {
                item(key = "monitor_search_empty") {
                    MonitorEmptyState(state, monitorViewModel::loadMore)
                }
            }
        },
        actions = {
            ActionButton(
                MiuixIcons.Delete,
                stringResource(R.string.file_monitor_clear),
                enabled = !state.isLoading && !state.isLoadingMore && state.entries.isNotEmpty(),
                backgroundColor = Color.Transparent,
                onClick = { isClearDialogVisible = true },
            )
            ActionButton(
                MiuixIcons.Refresh,
                stringResource(R.string.file_monitor_refresh),
                enabled = !state.isLoading,
                backgroundColor = Color.Transparent,
                onClick = monitorViewModel::refresh,
            )
            OverlayIconDropdownMenu(
                entry = filterEntry,
                modifier = Modifier.padding(horizontal = 4.dp).testTag("monitor_filter_menu"),
                minHeight = 35.dp,
                minWidth = 35.dp,
                backgroundColor = Color.Transparent,
                onExpandedChange = monitorViewModel::setOpTypeMenuVisible,
            ) {
                Icon(MiuixIcons.Tune, stringResource(R.string.apps_filter), Modifier.size(20.dp))
            }
        },
    ) {
        item { SmallTitle("${filters[state.opTypeIndex]}  ${filtered.size}") }
        if (!state.isMonitoringEnabled)
            item {
                Text(stringResource(R.string.file_monitor_disabled),
                    Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        if (state.hasControlError)
            item {
                Text(stringResource(R.string.file_monitor_control_failed),
                    Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = StatusColors.danger)
            }
        if (state.lostRecords > 0 || state.writeErrors > 0)
            item {
                Text(
                    stringResource(
                        R.string.file_monitor_gaps,
                        state.lostRecords,
                        state.writeErrors,
                    ),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    color = StatusColors.danger,
                )
            }
        if (state.isLegacy)
            item {
                Text(
                    stringResource(R.string.file_monitor_legacy),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
        if (state.hasLoadError || state.daemonStale)
            item {
                Text(
                    stringResource(
                        if (state.hasLoadError) R.string.file_monitor_load_failed
                        else R.string.file_monitor_daemon_stale
                    ),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    color = StatusColors.danger,
                )
            }
        if (state.isLoading && filtered.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (filtered.isEmpty()) {
            item(key = "monitor_empty") { MonitorEmptyState(state, monitorViewModel::loadMore) }
        }
        itemsIndexed(
            filtered,
            key = { index, entry -> "$index:${entry.timestamp}:${entry.path}" },
            contentType = { _, _ -> "monitor" },
        ) { _, entry ->
            MonitorEntryCard(entry, names[entry.actorIdentity] ?: entry.actorIdentity,
                icons[entry.actorIdentity])
        }
        if (state.hasMore && filtered.isNotEmpty())
            item(key = "monitor_load_more") { MonitorLoadMore(state, monitorViewModel::loadMore) }

    }
    MonitorClearConfirmationDialog(
        isVisible = isClearDialogVisible,
        onDismiss = { isClearDialogVisible = false },
        onConfirm = {
            isClearDialogVisible = false
            monitorViewModel.clear()
        },
    )
}

@Composable
private fun MonitorClearConfirmationDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = isVisible,
        title = stringResource(R.string.file_monitor_clear),
        summary = stringResource(R.string.file_monitor_clear_confirm),
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.common_delete),
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonitorEmptyState(state: FileMonitorUiState, onLoadMore: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.file_monitor_empty), fontSize = 16.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (state.hasMore) {
            Spacer(Modifier.height(12.dp))
            MonitorLoadMore(state, onLoadMore)
        }
    }
}

@Composable
private fun MonitorLoadMore(state: FileMonitorUiState, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(24.dp))
        else TextButton(
            text = stringResource(R.string.file_monitor_load_more),
            onClick = onLoadMore,
            modifier = Modifier.testTag("monitor_load_more"),
        )
    }
}

@Composable
private fun MonitorEntryCard(entry: FileMonitorEntry, appName: String, icon: Drawable?) {
    var expanded by rememberSaveable(entry.timestamp, entry.path) { mutableStateOf(false) }
    val action =
        stringResource(
            when (entry.actionType) {
                MonitorActionType.OPEN -> R.string.file_monitor_action_open
                MonitorActionType.CREATE -> R.string.file_monitor_action_create
                MonitorActionType.DELETE -> R.string.file_monitor_action_delete
                MonitorActionType.RENAME -> R.string.file_monitor_action_rename
                MonitorActionType.READ -> R.string.file_monitor_action_read
                MonitorActionType.WRITE -> R.string.file_monitor_action_write
                MonitorActionType.TRUNCATE -> R.string.file_monitor_action_truncate
                MonitorActionType.MAP -> R.string.file_monitor_action_map
                MonitorActionType.SYNC -> R.string.file_monitor_action_sync
            }
        )
    val source =
        stringResource(
            if (entry.isDelegatedBySystemWriter)
                when (entry.delegatedWriterType) {
                    MonitorWriterType.MEDIA -> R.string.file_monitor_source_media_action
                    MonitorWriterType.DOWNLOAD -> R.string.file_monitor_source_download_action
                    MonitorWriterType.MTP -> R.string.file_monitor_source_mtp_action
                    MonitorWriterType.SYSTEM -> R.string.file_monitor_source_system_writer_action
                    MonitorWriterType.NONE -> R.string.file_monitor_source_filesystem_action
                }
            else R.string.file_monitor_source_filesystem_action,
            action,
        )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.fillMaxWidth().sheetHeightTransition().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, Modifier.size(32.dp), MiuixTheme.colorScheme.secondaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    appName,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(entry.timestamp.substringAfter(' ').take(8),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                ActionButton(
                    if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
                    stringResource(
                        if (expanded) R.string.file_monitor_collapse
                        else R.string.file_monitor_expand
                    ),
                    onClick = { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (entry.count > 1)
                        stringResource(R.string.file_monitor_repeated, source, entry.count)
                    else source,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(
                    stringResource(
                        if (entry.isSuccess) R.string.file_monitor_result_success
                        else R.string.file_monitor_result_failed
                    ),
                    if (entry.isSuccess) StatusColors.healthy else StatusColors.danger,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                Text(
                    "${entry.timestamp}  ${entry.bytes} B",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    "${entry.packageName} · ${entry.caller} · pid=${entry.processId} · ret=${entry.nativeResult} · errno=${entry.errorNo}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                entry.extra?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                entry.displayPath,
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
