package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.storage.redirect.x.R
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun BrowserSelectionDock(state: PathBrowserState, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        if (state.allowExclude) {
            val labels = listOf(stringResource(R.string.browser_allow_access), stringResource(R.string.browser_exclude_directory))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                val width = (maxWidth - 15.dp) / 2
                TabRowWithContour(tabs = labels, selectedTabIndex = if (state.isExcludeMode) 1 else 0,
                    onTabSelected = { state.isExcludeMode = it == 1 }, minWidth = width, maxWidth = width,
                    modifier = Modifier.testTag("browser_rule_mode"))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                if (state.multiSelect) {
                    BrowserText(stringResource(R.string.browser_selected_count, state.selectedPaths.size),
                        Modifier.clickable { state.dialog = BrowserDialog.Selection }
                            .padding(vertical = 12.dp).testTag("browser_selected_paths"),
                        color = browserColors().primary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                } else {
                    BrowserText(stringResource(R.string.browser_current_location), color = browserColors().summary, fontSize = 12.sp)
                    BrowserText(state.currentPath.ifEmpty { stringResource(R.string.browser_enter_directory) }, maxLines = 1)
                }
            }
            Box(Modifier.testTag("browser_confirm")) {
                BrowserButton(text = stringResource(R.string.common_ok), onClick = onConfirm,
                    enabled = state.canConfirm, primary = true,
                    modifier = Modifier.testTag("directory_confirm"))
            }
        }
    }
}
