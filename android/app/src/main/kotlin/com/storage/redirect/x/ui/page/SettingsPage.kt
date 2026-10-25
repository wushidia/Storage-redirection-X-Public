package com.storage.redirect.x.ui.page

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storage.redirect.x.R
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.cache.PreferenceKeys
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.TemplateRepository
import com.storage.redirect.x.ui.component.*
import com.storage.redirect.x.ui.navigation.LocalNavigator
import com.storage.redirect.x.util.BackupRestoreNotifier
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsPage(bottomInnerPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val navigator = LocalNavigator.current
    val vm: SettingsViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val canOperate = state.hasRoot == true && !state.isWorking
    val templates = TemplateRepository().loadTemplates()
    val templateIds = listOf("") + templates.map { it.id }
    val templateNames = listOf(stringResource(R.string.auto_redirect_default_rules)) + templates.map { it.name }
    var language by remember {
        mutableIntStateOf(AppPreferences.getInt(PreferenceKeys.LANGUAGE, 0))
    }
    val languages =
        listOf(
            stringResource(R.string.language_system),
            stringResource(R.string.language_en),
            stringResource(R.string.language_zh_cn),
        )
    val backup =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null && canOperate) vm.exportPackageConfigsZip(context.contentResolver, uri)
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && canOperate)
                vm.restorePackageConfigsFromUri(context.contentResolver, uri)
        }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                SettingsUiEvent.SaveFailed ->
                    Toast.makeText(
                            context,
                            resources.getString(R.string.file_monitor_save_failed),
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                is SettingsUiEvent.BackupFinished -> {
                    val r = event.result
                    Toast.makeText(
                            context,
                            resources.getQuantityString(
                                if (r.isSuccess) R.plurals.settings_backup_success_with_count
                                else R.plurals.settings_backup_failed_with_count,
                                r.packageCount, r.packageCount,
                            ),
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                    BackupRestoreNotifier.notifyBackupResult(context, r.isSuccess, r.packageCount)
                }
                is SettingsUiEvent.RestoreFinished -> {
                    val r = event.result
                    Toast.makeText(
                            context,
                            resources.getQuantityString(
                                if (r.isSuccess) R.plurals.settings_restore_success_with_count
                                else R.plurals.settings_restore_failed_with_count,
                                r.restoredPackageCount, r.restoredPackageCount,
                            ),
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                    BackupRestoreNotifier.notifyRestoreResult(
                        context,
                        r.isSuccess,
                        r.restoredPackageCount,
                    )
                }
            }
            vm.refreshRootStatus()
        }
    }
    ScreenScaffold(
        title = stringResource(R.string.nav_settings),
        bottomPadding = bottomInnerPadding,
        modifier = Modifier.testTag("page_settings"),
    ) {
        item { SmallTitle(stringResource(R.string.settings_appearance)) }
        groupedCardItems(
            "appearance",
            listOf(
                CardItem("theme") {
                    ArrowPreference(
                        title = stringResource(R.string.settings_theme_title),
                        onClick = { navigator.push("theme") },
                    )
                },
                CardItem("language") {
                    ChoicePreference(
                        title = stringResource(R.string.language_title),
                        items = languages,
                        selectedIndex = language.coerceIn(languages.indices),
                        onSelect = {
                            if (language != it) {
                                language = it
                                AppPreferences.put(PreferenceKeys.LANGUAGE, it)
                                (context as? Activity)?.recreate()
                            }
                        },
                    )
                },
            ),
        )
        item { SmallTitle(stringResource(R.string.settings_tools)) }
        groupedCardItems(
            "tools",
            listOf(
                CardItem("fuse") {
                    SwitchPreference(
                        title = stringResource(R.string.settings_fuse_fixer),
                        summary =
                            if (state.hasRoot != true)
                                stringResource(R.string.settings_backup_restore_root_required)
                            else if (state.isWorking)
                                stringResource(R.string.settings_action_running)
                            else null,
                        checked = state.isFuseFixerEnabled,
                        enabled = canOperate,
                        onCheckedChange = vm::setFuseFixerEnabled,
                    )
                },
                CardItem("logs") {
                    ArrowPreference(
                        title = stringResource(R.string.logs_title),
                        onClick = { navigator.push("logs") },
                    )
                },
                CardItem("templates") {
                    ArrowPreference(
                        title = stringResource(R.string.apps_templates),
                        onClick = { navigator.push("templates") },
                    )
                },
                CardItem("auto_redirect") {
                    SwitchPreference(
                        title = stringResource(R.string.auto_redirect_new_apps),
                        checked = state.autoRedirectPolicy.isEnabled,
                        enabled = canOperate,
                        onCheckedChange = { vm.setAutoRedirectPolicy(state.autoRedirectPolicy.copy(isEnabled = it)) },
                    )
                },
                CardItem("auto_redirect_template") {
                    ChoicePreference(
                        title = stringResource(R.string.auto_redirect_template),
                        items = templateNames,
                        selectedIndex = templateIds.indexOf(state.autoRedirectPolicy.templateId),
                        summary = if (state.autoRedirectPolicy.templateId !in templateIds)
                            stringResource(R.string.auto_redirect_template_missing) else null,
                        enabled = canOperate && state.autoRedirectPolicy.isEnabled,
                        onSelect = { vm.setAutoRedirectPolicy(state.autoRedirectPolicy.copy(templateId = templateIds[it])) },
                    )
                },
            ),
        )
        item { SmallTitle(stringResource(R.string.nav_monitor)) }
        groupedCardItems(
            "monitor",
            listOf(
                CardItem("monitor_enabled") {
                    SwitchPreference(
                        title = stringResource(R.string.file_monitor_enabled),
                        checked = state.isFileMonitorEnabled,
                        enabled = canOperate,
                        onCheckedChange = vm::setFileMonitorEnabled,
                    )
                },
                CardItem("retention") {
                    ChoicePreference(
                        title = stringResource(R.string.file_monitor_retention),
                        items =
                            listOf(
                                stringResource(R.string.file_monitor_keep_hour),
                                stringResource(R.string.file_monitor_keep_six_hours),
                                stringResource(R.string.file_monitor_keep_day),
                                stringResource(R.string.file_monitor_keep_three_days),
                                stringResource(R.string.file_monitor_keep_week),
                                stringResource(R.string.file_monitor_keep_month),
                                stringResource(R.string.file_monitor_keep_forever),
                            ),
                        selectedIndex =
                            ConfigRepository.MONITOR_RETENTION_OPTIONS.indexOf(
                                    state.fileMonitorRetentionSeconds
                                )
                                .coerceAtLeast(0),
                        enabled = canOperate,
                        onSelect = {
                            vm.setFileMonitorRetention(
                                ConfigRepository.MONITOR_RETENTION_OPTIONS[it]
                            )
                        },
                    )
                }
            ),
        )
        item { SmallTitle(stringResource(R.string.settings_backup_restore)) }
        groupedCardItems(
            "backup",
            listOf(
                CardItem("export") {
                    ArrowPreference(
                        title = stringResource(R.string.settings_backup_title),
                        enabled = canOperate,
                        onClick = {
                            backup.launch(
                                "storage_redirect_x_apps_backup_${System.currentTimeMillis()}.zip"
                            )
                        },
                    )
                },
                CardItem("restore") {
                    ArrowPreference(
                        title = stringResource(R.string.settings_restore_title),
                        enabled = canOperate,
                        onClick = {
                            restore.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                    "application/json",
                                    "text/plain",
                                )
                            )
                        },
                    )
                },
            ),
        )
        item { SmallTitle(stringResource(R.string.settings_about)) }
        groupedCardItems(
            "about",
            listOf(
                CardItem("entry") {
                    ArrowPreference(
                        title = stringResource(R.string.settings_about),
                        modifier = Modifier.testTag("settings_about_entry"),
                        onClick = { navigator.push("about") },
                    )
                }
            ),
        )
    }
}
