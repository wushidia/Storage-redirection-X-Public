package com.storage.redirect.x.debug

import android.os.Bundle
import com.google.gson.Gson

internal data class DebugCommandSpec(
    val method: String,
    val arg: String?,
    val extras: Map<String, String>,
    val isWrite: Boolean = false,
)

private val userExtras = mapOf("user_id" to "i: optional, default 0")
private val listExtras = mapOf("filter" to "s: optional", "limit" to "i: optional, max 500")
private val configExtras = mapOf("config_base64" to "s: Base64 JSON for adb; enabled/allowed_real_paths/path_mappings", "config" to "s: raw JSON for direct provider calls; mutually exclusive with config_base64")
internal val debugCommandSpecs = listOf(
    DebugCommandSpec("commands.help", "optional method or domain prefix", emptyMap()),
    DebugCommandSpec(METHOD_PAGE_OPEN, "required page id", emptyMap()),
    DebugCommandSpec(METHOD_APPS_LIST, "optional filter", listExtras + userExtras),
    DebugCommandSpec(METHOD_APPS_SHARED_UID, "required package", emptyMap()),
    DebugCommandSpec(METHOD_BROWSER_LIST, "optional relative directory", mapOf("limit" to "i: optional, max 500") + userExtras),
    DebugCommandSpec(METHOD_MONITOR_RECENT, null, mapOf("limit" to "i: optional, max 500")),
    DebugCommandSpec(METHOD_CONFIG_GET, "required package", userExtras),
    DebugCommandSpec(METHOD_CONFIG_LIST, "optional package filter", listExtras + userExtras),
    DebugCommandSpec(METHOD_CONFIG_SET, "required package", userExtras + configExtras, true),
    DebugCommandSpec(METHOD_CONFIG_DELETE, "required package", userExtras, true),
    DebugCommandSpec(METHOD_CONFIG_CLEAR, "required package", userExtras, true),
    DebugCommandSpec(METHOD_CONFIG_SET_ENABLED, "required package", userExtras + ("enabled" to "b: required"), true),
    DebugCommandSpec(METHOD_CONFIG_ALLOWED_ADD, "required package", userExtras + ("path" to "s: required relative path"), true),
    DebugCommandSpec(METHOD_CONFIG_ALLOWED_REMOVE, "required package", userExtras + ("path" to "s: required relative path"), true),
    DebugCommandSpec(METHOD_CONFIG_MAPPING_SET, "required package", userExtras + mapOf("request_path" to "s: required", "final_path" to "s: required"), true),
    DebugCommandSpec(METHOD_CONFIG_MAPPING_REMOVE, "required package", userExtras + ("request_path" to "s: required"), true),
    DebugCommandSpec(METHOD_TEMPLATES_LIST, null, mapOf("limit" to "i: optional, max 500")),
    DebugCommandSpec("templates.get", "required template id", emptyMap()),
    DebugCommandSpec("templates.save", "optional existing template id", mapOf("name" to "s: required for creation") + configExtras, true),
    DebugCommandSpec("templates.delete", "required template id", emptyMap(), true),
    DebugCommandSpec("templates.from_app", "required package", userExtras + ("name" to "s: required"), true),
    DebugCommandSpec("templates.apply", "required package", userExtras + mapOf("template_id" to "s: required", "mode" to "s: required merge/replace"), true),
)

internal fun runCommandsHelpCommand(filter: String?): Bundle {
    val commands = debugCommandSpecs.filter { filter.isNullOrBlank() || it.method.startsWith(filter) }
    return debugResult(
        isOk = true,
        message = "rows=${commands.size}",
        command = "commands.help",
        target = filter,
        value = Gson().toJson(commands),
    )
}
