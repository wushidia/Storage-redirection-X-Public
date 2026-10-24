package com.storage.redirect.x.ui.page.pathbrowser

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BrowserColors(
    val background: Color, val surface: Color, val text: Color, val summary: Color,
    val primary: Color, val selected: Color, val divider: Color, val error: Color,
)

@Composable
internal fun browserColors() = MiuixTheme.colorScheme.let {
    BrowserColors(it.surface, it.surfaceContainer, it.onSurface, it.onSurfaceVariantSummary,
        it.primary, it.tertiaryContainer, it.dividerLine, it.error)
}

@Composable
internal fun BrowserText(
    text: String, modifier: Modifier = Modifier, color: Color = browserColors().text,
    fontSize: TextUnit = 14.sp, fontWeight: FontWeight? = null, maxLines: Int = Int.MAX_VALUE,
) {
    Text(text = text, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun BrowserIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) { content() }
}

@Composable
internal fun BrowserButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false,
) {
    TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
internal fun BrowserCheckbox(isSelected: Boolean, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), Alignment.Center) {
        Checkbox(state = ToggleableState(isSelected), onClick = onClick,
            modifier = modifier.semantics { contentDescription = description })
    }
}
