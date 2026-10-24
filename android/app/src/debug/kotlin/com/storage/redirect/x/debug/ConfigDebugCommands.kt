package com.storage.redirect.x.debug

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.storage.redirect.x.data.repository.ConfigRepository
import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.model.StoragePathMapping
import com.storage.redirect.x.data.service.RootService
import kotlinx.coroutines.runBlocking

internal fun Context.runConfigGetCommand(packageName: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_CONFIG_GET, packageName) {
    val target = debugPackageName(packageName)
    val userId = debugUserId(extras)
    runBlocking {
        require(RootService.isRootAvailable()) { "Root unavailable" }
        val config = ConfigRepository().loadPackages(userId, listOf(target))
        val appConfig = requireNotNull(config.getAppConfig(target)) { "No config for $target (user $userId)" }
        debugResult(
            isOk = true,
            message = "enabled=${appConfig.isEnabled} allowed=${appConfig.allowedRealPaths.size} mappings=${appConfig.pathMappings.size} user_id=$userId",
            command = METHOD_CONFIG_GET,
            target = target,
            value = Gson().toJson(appConfig),
        )
    }
}

internal fun Context.runConfigListCommand(filter: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_CONFIG_LIST, filter) {
    val userId = debugUserId(extras)
    val filterText = extras.optionalString(EXTRA_FILTER) ?: filter
    val limit = extras.optionalInt(EXTRA_LIMIT, 0).coerceIn(0, MAX_LIST_ROWS).let { if (it == 0) MAX_LIST_ROWS else it }
    runBlocking {
        require(RootService.isRootAvailable()) { "Root unavailable" }
        val configs = ConfigRepository().load(userId).appConfigs.filter {
            filterText.isNullOrBlank() || it.packageName.contains(filterText, ignoreCase = true)
        }
        val rows = configs.take(limit)
        debugResult(
            isOk = true,
            message = "matched=${configs.size} rows=${rows.size} user_id=$userId",
            command = METHOD_CONFIG_LIST,
            target = filterText,
            value = Gson().toJson(rows),
        )
    }
}

internal fun Context.runConfigMutationCommand(command: String, packageName: String?, extras: Bundle?): Bundle = runDebugCommand(command, packageName) {
    val target = debugPackageName(packageName)
    val userId = debugUserId(extras)
    val json = if (command == METHOD_CONFIG_SET) JsonParser.parseString(extras.requiredDebugConfigText()).asJsonObject else null
    val isEnabled = if (command == METHOD_CONFIG_SET_ENABLED) extras.requiredBoolean(EXTRA_ENABLED) else null
    val path = if (command == METHOD_CONFIG_ALLOWED_ADD || command == METHOD_CONFIG_ALLOWED_REMOVE) {
        debugAllowedPath(extras.requiredString(EXTRA_PATH))
    } else null
    val requestPath = if (command == METHOD_CONFIG_MAPPING_SET || command == METHOD_CONFIG_MAPPING_REMOVE) {
        debugMappingPath(extras.requiredString(EXTRA_REQUEST_PATH))
    } else null
    val finalPath = if (command == METHOD_CONFIG_MAPPING_SET) debugMappingPath(extras.requiredString(EXTRA_FINAL_PATH)) else null
    runBlocking {
        val targets = loadDebugConfigTargets(target, userId)
        if (command == METHOD_CONFIG_DELETE) return@runBlocking deleteDebugAppConfigs(command, target, targets)
        val current = targets.config.getAppConfig(target)
        val next = when (command) {
            METHOD_CONFIG_SET -> parseDebugAppConfig(target, requireNotNull(json), current)
            METHOD_CONFIG_SET_ENABLED -> (current ?: AppRedirectConfig(target)).copy(isEnabled = requireNotNull(isEnabled))
            METHOD_CONFIG_CLEAR -> requireNotNull(current) { "No config for $target" }.copy(allowedRealPaths = emptyList(), pathMappings = emptyList())
            METHOD_CONFIG_ALLOWED_ADD -> (current ?: AppRedirectConfig(target)).copy(allowedRealPaths = current?.allowedRealPaths.orEmpty() + requireNotNull(path))
            METHOD_CONFIG_ALLOWED_REMOVE -> {
                require(current != null && path in current.allowedRealPaths) { "Allowed path not found: $path" }
                current.copy(allowedRealPaths = current.allowedRealPaths.filter { it != path })
            }
            METHOD_CONFIG_MAPPING_SET -> {
                require(requestPath != finalPath) { "Mapping paths must differ" }
                (current ?: AppRedirectConfig(target)).copy(
                    pathMappings = current?.pathMappings.orEmpty().filter { it.requestPath != requestPath } +
                        StoragePathMapping(requireNotNull(requestPath), requireNotNull(finalPath)),
                )
            }
            METHOD_CONFIG_MAPPING_REMOVE -> {
                require(current != null && current.pathMappings.any { it.requestPath == requestPath }) { "Mapping not found: $requestPath" }
                current.copy(pathMappings = current.pathMappings.filter { it.requestPath != requestPath })
            }
            else -> error("Unknown mutation: $command")
        }
        val updated = targets.packageNames.fold(targets.config) { config, name -> config.setAppConfig(name, next) }
        saveDebugAppConfigs(command, target, targets, updated)
    }
}
