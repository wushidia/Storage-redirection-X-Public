package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.storage.redirect.x.R
import com.storage.redirect.x.ui.component.SrxScaffold
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.squircle.squircleSurface

@Composable
internal fun BrowserScaffold(
    title: String, state: PathBrowserState, onBack: () -> Unit,
    onConfirm: () -> Unit, modifier: Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    SrxScaffold(
        modifier = modifier.imePadding(),
        topBar = { BrowserSelectionHeader(title, state, onBack, onConfirm) },
        content = content,
    )
}

@Composable
internal fun BrowserBreadcrumbs(state: PathBrowserState) {
    val scroll = rememberScrollState()
    val parts = state.currentPath.split('/').filter { it.isNotEmpty() }
    LaunchedEffect(state.currentPath, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            BreadcrumbButton(stringResource(if (state.packageName == null) R.string.browser_storage
                else R.string.browser_private_storage), Modifier.testTag("browser_root"),
                isCurrent = parts.isEmpty(), leading = {
                    Image(painterResource(if (state.packageName == null) R.drawable.browser_storage
                        else R.drawable.browser_folder), null, Modifier.size(24.dp))
                }) { state.navigateTo("") }
            parts.forEachIndexed { index, name ->
                Icon(MiuixIcons.ChevronForward, null, Modifier.size(14.dp), tint = browserColors().summary)
                BreadcrumbButton(name, isCurrent = index == parts.lastIndex) {
                    state.navigateTo(parts.take(index + 1).joinToString("/"))
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbButton(text: String, modifier: Modifier = Modifier, isCurrent: Boolean,
    leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Box(modifier.squircleSurface(color = Color.Transparent, cornerRadius = 8.dp)
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            BrowserText(text, maxLines = 1, fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                color = if (isCurrent) browserColors().text else browserColors().summary)
        }
    }
}

@Composable
internal fun BrowserSearch(query: String, onQueryChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    SearchBar(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        insideMargin = DpSize(12.dp, 0.dp),
        inputField = {
            InputField(query = query, onQueryChange = onQueryChange, onSearch = { focus.clearFocus() },
                expanded = false, onExpandedChange = {}, label = stringResource(R.string.browser_search),
                leadingIcon = { Icon(MiuixIcons.Search, null, Modifier.padding(horizontal = 12.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) BrowserIconButton({ onQueryChange("") }) {
                        Icon(MiuixIcons.Close, stringResource(R.string.browser_clear_search))
                    }
                }, modifier = Modifier.testTag("browser_search"))
        }, onExpandedChange = {},
    ) {}
}
