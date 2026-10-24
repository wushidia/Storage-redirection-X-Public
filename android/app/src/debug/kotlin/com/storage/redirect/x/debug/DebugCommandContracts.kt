package com.storage.redirect.x.debug

import android.os.Bundle
import android.util.Base64

internal const val METHOD_PAGE_OPEN = "page.open"
internal const val METHOD_APPS_LIST = "apps.list"
internal const val METHOD_APPS_SHARED_UID = "apps.shareduid"
internal const val METHOD_BROWSER_LIST = "browser.list"
internal const val METHOD_MONITOR_RECENT = "monitor.recent"
internal const val METHOD_CONFIG_GET = "config.get"
internal const val METHOD_TEMPLATES_LIST = "templates.list"
internal const val METHOD_CONFIG_LIST = "config.list"
internal const val METHOD_CONFIG_SET = "config.set"
internal const val METHOD_CONFIG_DELETE = "config.delete"
internal const val METHOD_CONFIG_CLEAR = "config.clear"
internal const val METHOD_CONFIG_SET_ENABLED = "config.set_enabled"
internal const val METHOD_CONFIG_ALLOWED_ADD = "config.allowed.add"
internal const val METHOD_CONFIG_ALLOWED_REMOVE = "config.allowed.remove"
internal const val METHOD_CONFIG_MAPPING_SET = "config.mapping.set"
internal const val METHOD_CONFIG_MAPPING_REMOVE = "config.mapping.remove"

internal const val EXTRA_FILTER = "filter"
internal const val EXTRA_LIMIT = "limit"
internal const val EXTRA_USER_ID = "user_id"
internal const val EXTRA_CONFIG = "config"
internal const val EXTRA_CONFIG_BASE64 = "config_base64"
internal const val EXTRA_ENABLED = "enabled"
internal const val EXTRA_PATH = "path"
internal const val EXTRA_REQUEST_PATH = "request_path"
internal const val EXTRA_FINAL_PATH = "final_path"

private const val KEY_OK = "ok"
private const val KEY_MESSAGE = "message"
private const val KEY_COMMAND = "command"
private const val KEY_TARGET = "target"
private const val KEY_VALUE = "value"

// Binder 传输上限约 1MB，列表类指令统一截断到该行数
internal const val MAX_LIST_ROWS = 500
private const val MAX_VALUE_BYTES = 512 * 1024

internal fun debugResult(
    isOk: Boolean,
    message: String,
    command: String? = null,
    target: String? = null,
    value: String? = null,
): Bundle = Bundle().apply {
    val isTooLarge = value != null && value.length.toLong() * 2 > MAX_VALUE_BYTES
    putBoolean(KEY_OK, isOk && !isTooLarge)
    putString(KEY_MESSAGE, if (isTooLarge) "$message; response exceeds 512 KiB, reduce limit or query a single item" else message)
    putString(KEY_COMMAND, command)
    putString(KEY_TARGET, target)
    putString(KEY_VALUE, if (isTooLarge) null else value)
}

// 命中异常统一转失败结果，避免把堆栈抛给调用方
internal inline fun runDebugCommand(
    command: String,
    target: String?,
    block: () -> Bundle,
): Bundle = try {
    block()
} catch (e: Exception) {
    debugResult(isOk = false, message = e.message ?: "Failed: $command", command = command, target = target)
}

// 参数类型不符时直接失败，防止写入错误用户或意外禁用配置
internal fun Bundle?.optionalInt(key: String, default: Int): Int {
    if (this == null || !containsKey(key)) return default
    val value = extraValue(key)
    require(value is Int) { "$key must be an integer extra (:i:)" }
    return value
}

internal fun Bundle?.optionalString(key: String): String? {
    if (this == null || !containsKey(key)) return null
    val value = extraValue(key)
    require(value is String && value.isNotBlank()) { "$key must be a non-empty string extra (:s:)" }
    return value
}

internal fun Bundle?.requiredString(key: String): String =
    requireNotNull(optionalString(key)) { "Missing $key" }

internal fun Bundle?.requiredBoolean(key: String): Boolean {
    val value = this?.extraValue(key)
    require(value is Boolean) { "$key must be a boolean extra (:b:)" }
    return value
}

// content 工具按冒号拆分 extra，JSON 经 Base64 传输后再还原。
internal fun Bundle?.optionalDebugConfigText(): String? {
    val config = optionalString(EXTRA_CONFIG)
    val encoded = optionalString(EXTRA_CONFIG_BASE64)
    require(config == null || encoded == null) { "Provide config or config_base64, not both" }
    return if (encoded == null) config else Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
}

internal fun Bundle?.requiredDebugConfigText(): String =
    requireNotNull(optionalDebugConfigText()) { "Missing config_base64" }

@Suppress("DEPRECATION")
private fun Bundle.extraValue(key: String): Any? = get(key)

internal fun debugUserId(extras: Bundle?): Int = extras.optionalInt(EXTRA_USER_ID, 0).also {
    require(it >= 0) { "user_id must be non-negative" }
}

internal fun debugPackageName(packageName: String?): String {
    require(!packageName.isNullOrBlank()) { "Missing package name" }
    require(packageName.length <= 255 && packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
        "Invalid package name"
    }
    return packageName
}
