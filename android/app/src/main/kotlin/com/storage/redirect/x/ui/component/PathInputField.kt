package com.storage.redirect.x.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.storage.redirect.x.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun PathInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    browseModifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            singleLine = singleLine,
            modifier = Modifier.weight(1f).then(inputModifier),
        )
        TextButton(
            text = stringResource(R.string.rule_config_browse),
            onClick = onBrowse,
            modifier = browseModifier,
        )
    }
}
