package com.storage.redirect.x.debug

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.collections.immutable.toImmutableList
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.model.RedirectTemplate
import com.storage.redirect.x.data.repository.TemplateApplyMode
import com.storage.redirect.x.data.repository.TemplateRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val METHOD_TEMPLATES_GET = "templates.get"
private const val METHOD_TEMPLATES_SAVE = "templates.save"
private const val METHOD_TEMPLATES_DELETE = "templates.delete"
private const val METHOD_TEMPLATES_FROM_APP = "templates.from_app"
private const val METHOD_TEMPLATES_APPLY = "templates.apply"
private const val EXTRA_NAME = "name"
private const val EXTRA_TEMPLATE_ID = "template_id"
private const val EXTRA_MODE = "mode"
private const val KEY_REDIRECT_TEMPLATES = "redirect_templates"

internal fun Context.runTemplatesListCommand(extras: Bundle?): Bundle = runDebugCommand(METHOD_TEMPLATES_LIST, null) {
    val templates = TemplateRepository().loadTemplates()
    val limit = extras.optionalInt(EXTRA_LIMIT, 0)
    require(limit >= 0) { "limit must be non-negative" }
    val rows = templates.take(if (limit == 0) MAX_LIST_ROWS else limit.coerceAtMost(MAX_LIST_ROWS))
    debugResult(
        isOk = true,
        message = "rows=${rows.size} total=${templates.size}",
        command = METHOD_TEMPLATES_LIST,
        value = Gson().toJson(rows),
    )
}

internal fun Context.runTemplatesGetCommand(templateId: String?): Bundle = runDebugCommand(METHOD_TEMPLATES_GET, templateId) {
    val template = findDebugTemplate(templateId)
    debugResult(
        isOk = true,
        message = "allowed=${template.allowedRealPaths.size} mappings=${template.pathMappings.size}",
        command = METHOD_TEMPLATES_GET,
        target = template.id,
        value = Gson().toJson(template),
    )
}

internal fun Context.runTemplatesSaveCommand(templateId: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_TEMPLATES_SAVE, templateId) {
    val existing = templateId?.let { findDebugTemplate(it) }
    val name = extras.optionalString(EXTRA_NAME)?.trim() ?: existing?.name
    require(!name.isNullOrBlank()) { "Missing name for new template" }
    val configText = extras.optionalDebugConfigText()
    require(existing == null || extras?.containsKey(EXTRA_NAME) == true || configText != null) { "Provide name or config" }
    val current = existing?.let {
        AppRedirectConfig("template", allowedRealPaths = it.allowedRealPaths, pathMappings = it.pathMappings)
    }
    val config = if (configText == null) {
        current ?: AppRedirectConfig("template")
    } else {
        val json = JsonParser.parseString(configText)
        require(json.isJsonObject) { "config must be a JSON object" }
        parseDebugAppConfig("template", json.asJsonObject, current)
    }
    val template = (existing ?: RedirectTemplate(name = name)).copy(
        name = name,
        allowedRealPaths = config.allowedRealPaths.toImmutableList(),
        pathMappings = config.pathMappings.toImmutableList(),
    )
    saveDebugTemplate(METHOD_TEMPLATES_SAVE, template)
}

internal fun Context.runTemplatesDeleteCommand(templateId: String?): Bundle = runDebugCommand(METHOD_TEMPLATES_DELETE, templateId) {
    val template = findDebugTemplate(templateId)
    val backupDir = backupDebugTemplates(METHOD_TEMPLATES_DELETE)
    val templates = TemplateRepository().deleteTemplate(template.id)
    debugResult(
        isOk = true,
        message = "Deleted total=${templates.size} backup_dir=${backupDir ?: "none"}",
        command = METHOD_TEMPLATES_DELETE,
        target = template.id,
        value = Gson().toJson(template),
    )
}

internal suspend fun Context.runTemplatesFromAppCommand(packageName: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_TEMPLATES_FROM_APP, packageName) {
    val name = extras.requiredString(EXTRA_NAME).trim()
    val target = debugPackageName(packageName)
    val userId = debugUserId(extras)
    val targets = loadDebugConfigTargets(target, userId)
    val config = targets.config.getAppConfig(target)
    requireNotNull(config) { "No config for $target (user $userId)" }
    val template = TemplateRepository().createTemplateFromApp(name, config)
    saveDebugTemplate(METHOD_TEMPLATES_FROM_APP, template)
}

internal suspend fun Context.runTemplatesApplyCommand(packageName: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_TEMPLATES_APPLY, packageName) {
    val target = debugPackageName(packageName)
    val userId = debugUserId(extras)
    val template = findDebugTemplate(extras.requiredString(EXTRA_TEMPLATE_ID))
    val mode = when (extras.requiredString(EXTRA_MODE)) {
        "merge" -> TemplateApplyMode.Merge
        "replace" -> TemplateApplyMode.Replace
        else -> error("mode must be merge or replace")
    }
    val targets = loadDebugConfigTargets(target, userId)
    val result = TemplateRepository().applyTemplate(
        config = targets.config,
        packageName = target,
        sharedPackages = targets.packageNames.filter { it != target },
        template = template,
        mode = mode,
    )
    saveDebugAppConfigs(METHOD_TEMPLATES_APPLY, target, targets, result.config)
}

private fun findDebugTemplate(templateId: String?): RedirectTemplate {
    require(!templateId.isNullOrBlank()) { "Missing template id" }
    return requireNotNull(TemplateRepository().loadTemplates().firstOrNull { it.id == templateId }) {
        "No template for $templateId"
    }
}

private fun Context.saveDebugTemplate(command: String, template: RedirectTemplate): Bundle {
    val backupDir = backupDebugTemplates(command)
    val templates = TemplateRepository().saveTemplate(template)
    val saved = templates.first { it.id == template.id }
    return debugResult(
        isOk = true,
        message = "Saved total=${templates.size} backup_dir=${backupDir ?: "none"}",
        command = command,
        target = saved.id,
        value = Gson().toJson(saved),
    )
}

// 模板共用一个偏好项，每次变更前备份完整原始内容。
private fun Context.backupDebugTemplates(command: String): String? {
    val content = AppPreferences.getString(KEY_REDIRECT_TEMPLATES)
    if (content.isEmpty()) return null
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    return backupDebugText(command, "redirect_templates.json.bak.$timestamp", content)
}
