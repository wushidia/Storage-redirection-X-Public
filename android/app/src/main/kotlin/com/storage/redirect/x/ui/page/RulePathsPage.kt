package com.storage.redirect.x.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.repository.PathBrowserRepository
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.page.pathbrowser.PathSelectionKind
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun RuleOptionsCard(
    isMappingModeOnly: Boolean,
    readOnlyCount: Int,
    onModeChange: (Boolean) -> Unit,
    onReadOnly: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        ArrowPreference(title = stringResource(R.string.rule_read_only_paths),
            summary = pluralStringResource(R.plurals.rule_path_count, readOnlyCount, readOnlyCount), onClick = onReadOnly,
            modifier = Modifier.testTag("rule_read_only_paths"))
        ChoicePreference(
            title = stringResource(R.string.rule_work_mode),
            items = listOf(stringResource(R.string.rule_mode_blacklist), stringResource(R.string.rule_mode_whitelist)),
            selectedIndex = if (isMappingModeOnly) 0 else 1,
            onSelect = { index ->
                val isBlacklistSelected = index == 0
                if (isBlacklistSelected != isMappingModeOnly) onModeChange(isBlacklistSelected)
            },
            modifier = Modifier.testTag("rule_mapping_mode_only"),
        )

    }
}

@Composable
internal fun RulePathsPage(
    paths: List<String>,
    userId: Int,
    browserRepository: PathBrowserRepository,
    onChange: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.rule_read_only_paths)
    var isBrowsing by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var input by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }
    val normalize = RedirectConfig::normalizePolicyPath

    fun edit(index: Int?) {
        editIndex = index
        input = index?.let { paths[it] }.orEmpty()
        hasError = false
        showEditor = true
    }

    DetailTransition(detail = if (isBrowsing) Unit else null,
        onBack = { isBrowsing = false; showEditor = true },
        detailContent = {
            PathBrowserPage(title, browserRepository, userId, multiSelect = editIndex == null, allowExclude = true,
                selectionKind = PathSelectionKind.Policy,
                isInitiallyExcluded = input.startsWith('!'),
                onBack = { isBrowsing = false; showEditor = true },
                onPathSelect = { input = it; isBrowsing = false; showEditor = true },
                onPathsSelect = { selected ->
                    onChange((paths + selected).mapNotNull(normalize).distinct())
                    isBrowsing = false
                })
        }) {
        ScreenScaffold(title = title, onBack = onBack,
            modifier = Modifier.testTag("page_policy_paths"),
            actions = { ActionButton(MiuixIcons.Add, stringResource(R.string.common_add),
                onClick = { edit(null) }, modifier = Modifier.testTag("policy_add"), backgroundColor = Color.Transparent) }) {
            item { Spacer(Modifier.height(12.dp)) }
            if (paths.isEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    BasicComponent(summary = stringResource(R.string.rule_paths_empty))
                }
            }
            itemsIndexed(paths, key = { index, value -> "$index:$value" }) { index, value ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    BasicComponent(title = value, onClick = { edit(index) },
                        endActions = {
                            AlignedIconButton(onClick = { onChange(paths.filterIndexed { item, _ -> item != index }) },
                                backgroundColor = Color.Transparent) {
                                Icon(MiuixIcons.Delete, stringResource(R.string.common_delete))
                            }
                        })
                }
            }
        }
        WindowDialog(show = showEditor && !isBrowsing, title = title,
            onDismissRequest = { showEditor = false }) {
            Column(Modifier.heightIn(max = 500.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    PathInputField(value = input, onValueChange = { input = it; hasError = false },
                        label = stringResource(R.string.rule_path_label),
                        onBrowse = { showEditor = false; isBrowsing = true },
                        inputModifier = Modifier.testTag("policy_path_input"),
                        browseModifier = Modifier.testTag("policy_browse"))
                    if (hasError) Text(stringResource(R.string.rule_config_browser_invalid_path),
                        color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(16.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(stringResource(R.string.common_cancel), onClick = { showEditor = false }, modifier = Modifier.weight(1f))
                    TextButton(stringResource(R.string.common_ok),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val path = normalize(input)
                            if (path == null) { hasError = true; return@TextButton }
                            val updated = paths.toMutableList()
                            val index = editIndex
                            if (index == null) updated.add(path) else updated[index] = path
                            onChange(updated.distinct())
                            showEditor = false
                        }, modifier = Modifier.weight(1f).testTag("policy_confirm"))
                }
            }
        }
    }
}


internal fun mappingProblemResId(problem: com.storage.redirect.x.data.model.MappingProblem): Int = when (problem) {
    com.storage.redirect.x.data.model.MappingProblem.MissingCapture -> R.string.rule_error_mapping_capture
    com.storage.redirect.x.data.model.MappingProblem.Cycle -> R.string.rule_error_mapping_cycle
    com.storage.redirect.x.data.model.MappingProblem.TooDeep -> R.string.rule_error_mapping_depth
}
