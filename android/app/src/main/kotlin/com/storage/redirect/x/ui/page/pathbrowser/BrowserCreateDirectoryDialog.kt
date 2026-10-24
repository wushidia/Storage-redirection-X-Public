package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import com.storage.redirect.x.data.repository.DirectoryCreateResult
import com.storage.redirect.x.data.repository.PathBrowserRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun BrowserCreateDirectoryDialog(state: PathBrowserState, repository: PathBrowserRepository) {
    var name by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    WindowDialog(show = true, title = stringResource(R.string.browser_new_folder),
        onDismissRequest = { if (!isCreating) state.dialog = null }) {
        TextField(value = name, onValueChange = { name = it; error = null },
            label = stringResource(R.string.browser_folder_name), singleLine = true, enabled = !isCreating,
            modifier = Modifier.fillMaxWidth().testTag("browser_folder_name"))
        error?.let { BrowserText(stringResource(it), color = browserColors().error,
            modifier = Modifier.padding(top = 8.dp).testTag("browser_create_error")) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowserButton(stringResource(R.string.common_cancel), { state.dialog = null },
                modifier = Modifier.weight(1f), enabled = !isCreating)
            BrowserButton(stringResource(R.string.common_ok), {
                isCreating = true
                scope.launch {
                    val result = repository.createDirectory(state.userId, state.packageName, state.currentPath, name)
                    isCreating = false
                    if (result == DirectoryCreateResult.Created) {
                        state.query = ""
                        state.reloadKey++
                        state.dialog = null
                    } else {
                        error = when (result) {
                            DirectoryCreateResult.AlreadyExists -> R.string.browser_folder_exists
                            DirectoryCreateResult.InvalidName -> R.string.browser_folder_invalid
                            else -> R.string.browser_create_failed
                        }
                    }
                }
            }, modifier = Modifier.weight(1f).testTag("browser_create_confirm"),
                enabled = !isCreating && name.isNotBlank(), primary = true)
        }
    }
}
