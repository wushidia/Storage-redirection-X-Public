package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun PathBrowserDialogs(state: PathBrowserState) {
    val dialog = state.dialog ?: return
    val title = when (dialog) {
        BrowserDialog.Settings -> stringResource(R.string.browser_settings)
        BrowserDialog.Location -> stringResource(R.string.browser_current_location)
        BrowserDialog.Selection -> stringResource(R.string.browser_selected_count, state.selectedPaths.size)
    }
    BrowserDialogWindow(title, { state.dialog = null }) {
        Column(Modifier.semantics { testTagsAsResourceId = BuildConfig.IS_DEBUG_BUILD }.testTag("browser_dialog")) {
            when (dialog) {
                BrowserDialog.Settings -> BrowserSettings(state)
                BrowserDialog.Location -> SelectionContainer {
                    BrowserText(state.absolutePath, modifier = Modifier.padding(bottom = 16.dp))
                }
                BrowserDialog.Selection -> BrowserSelectedPaths(state)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dialog == BrowserDialog.Selection && state.selectedPaths.isNotEmpty()) {
                    BrowserButton(
                        text = stringResource(R.string.browser_clear_selection),
                        onClick = state::clearSelection,
                        modifier = Modifier.weight(1f).testTag("browser_clear_selection"),
                    )
                }
                BrowserButton(
                    text = stringResource(R.string.common_close),
                    onClick = { state.dialog = null }, primary = true,
                    modifier = Modifier.weight(1f).testTag("browser_dialog_close"),
                )
            }
        }
    }
}

@Composable
private fun BrowserDialogWindow(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 500.dp)) { content() }
    }
}

@Composable
private fun BrowserSettings(state: PathBrowserState) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrowserText(stringResource(R.string.browser_show_hidden), modifier = Modifier.weight(1f))
        val modifier = Modifier.testTag("browser_show_hidden")
        Switch(checked = state.showHidden, onCheckedChange = { state.showHidden = it }, modifier = modifier)
    }
    BrowserButton(
        text = stringResource(R.string.browser_storage),
        onClick = { state.dialog = null; state.navigateTo("") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

@Composable
private fun BrowserSelectedPaths(state: PathBrowserState) {
    if (state.selectedPaths.isEmpty()) {
        BrowserText(
            stringResource(R.string.browser_no_selection), color = browserColors().summary,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(state.selectedPaths.toList(), key = { it }) { path ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable {
                        state.dialog = null
                        state.navigateTo(path)
                    }.padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BrowserText(path.substringAfterLast('/'), fontWeight = FontWeight.Medium, maxLines = 1)
                    BrowserText(path, fontSize = 12.sp, color = browserColors().summary, maxLines = 2)
                }
                BrowserIconButton(onClick = { state.togglePath(path) }, modifier = Modifier.testTag("browser_remove_$path")) {
                    Icon(
                        MiuixIcons.Close, stringResource(R.string.browser_remove_directory, path),
                        tint = browserColors().summary,
                    )
                }
            }
        }
    }
}
