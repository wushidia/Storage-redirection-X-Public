package com.storage.redirect.x.debug

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.StoragePathMapping
import com.storage.redirect.x.data.repository.ConfigRepository
import com.storage.redirect.x.data.repository.SharedUidRepository
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Paths
import java.io.File
import java.util.UUID

internal data class DebugConfigTargets(
    val repository: ConfigRepository,
    val config: RedirectConfig,
    val packageNames: Set<String>,
)

internal suspend fun Context.loadDebugConfigTargets(packageName: String, userId: Int): DebugConfigTargets {
    val sharedRepository = SharedUidRepository()
    check(sharedRepository.load()) { "Shared UID map unavailable (root required)" }
    val packageNames = linkedSetOf(packageName).apply { addAll(sharedRepository.getSharedPackages(packageName)) }
    for (name in packageNames) {
        debugPackageName(name)
        val content = readDebugConfigFile(Paths.appConfigFile(name)) ?: continue
        val users = JsonParser.parseString(content).asJsonObject.getAsJsonObject("users")
        check(users != null) { "Invalid existing config: $name" }
        users.getAsJsonObject(userId.toString())?.let { AppRedirectConfig.fromUserJson(name, it) }
    }
    val repository = ConfigRepository()
    return DebugConfigTargets(repository, repository.loadPackages(userId, packageNames), packageNames)
}

internal fun debugAllowedPath(raw: String): String {
    val validation = RedirectConfig.validateAllowedPath(raw)
    require(validation is PathValidationResult.Valid) { "Invalid allowed path: $raw ($validation)" }
    return validation.normalized
}

internal fun debugMappingPath(raw: String): String {
    val validation = RedirectConfig.validateMappingPath(raw)
    require(validation is PathValidationResult.Valid) { "Invalid mapping path: $raw ($validation)" }
    return validation.normalized
}

internal fun parseDebugAppConfig(packageName: String, json: JsonObject, existing: AppRedirectConfig? = null): AppRedirectConfig {
    require(json.keySet().all { it in setOf("enabled", "allowed_real_paths", "path_mappings") }) {
        "Config fields: enabled, allowed_real_paths, path_mappings"
    }
    val current = existing ?: AppRedirectConfig(packageName)
    val isEnabled = json.get("enabled")?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "enabled must be a boolean" }
        it.asBoolean
    } ?: current.isEnabled
    val allowedPaths = json.get("allowed_real_paths")?.let {
        require(it.isJsonArray) { "allowed_real_paths must be an array" }
        it.asJsonArray.map { path ->
            require(path.isJsonPrimitive && path.asJsonPrimitive.isString) { "Allowed path must be a string" }
            debugAllowedPath(path.asString)
        }
    } ?: current.allowedRealPaths
    val mappings = json.get("path_mappings")?.let {
        require(it.isJsonObject) { "path_mappings must be an object" }
        it.asJsonObject.entrySet().map { (request, final) ->
            require(final.isJsonPrimitive && final.asJsonPrimitive.isString) { "Mapping target must be a string" }
            val requestPath = debugMappingPath(request)
            val finalPath = debugMappingPath(final.asString)
            require(requestPath != finalPath) { "Mapping paths must differ" }
            StoragePathMapping(requestPath, finalPath)
        }
    } ?: current.pathMappings
    return current.copy(isEnabled = isEnabled, allowedRealPaths = allowedPaths, pathMappings = mappings)
}

internal fun Context.backupDebugText(command: String, fileName: String, content: String): String {
    val directory = File(filesDir, "debug_command_backups/${command}_${UUID.randomUUID()}")
    check(directory.mkdirs()) { "Cannot create backup directory" }
    File(directory, fileName).writeText(content)
    return directory.absolutePath
}

private suspend fun readDebugConfigFile(path: String): String? {
    val marker = "@@SRX_DEBUG_FILE@@"
    val result = RootService.runCommand("if [ -f '$path' ]; then printf '$marker'; cat '$path'; elif [ -e '$path' ]; then exit 1; fi")
    check(result.isSuccess) { "Cannot read config: $path" }
    if (result.stdout.isEmpty()) return null
    return result.stdout.removePrefix(marker)
}

private suspend fun Context.backupDebugAppConfigs(command: String, packageNames: Set<String>): String? {
    val contentsByFileName = linkedMapOf<String, String>()
    for (name in packageNames) {
        readDebugConfigFile(Paths.appConfigFile(name))?.let { contentsByFileName["$name.json"] = it }
        readDebugConfigFile(Paths.sharedAppConfigFile(name))?.let { contentsByFileName["$name.shared.json"] = it }
    }
    if (contentsByFileName.isEmpty()) return null
    val directory = File(filesDir, "debug_command_backups/${command}_${UUID.randomUUID()}")
    check(directory.mkdirs()) { "Cannot create backup directory" }
    for ((fileName, content) in contentsByFileName) File(directory, fileName).writeText(content)
    return directory.absolutePath
}

private suspend fun verifyDebugConfigMirrors(packageNames: Set<String>, shouldAllowAbsent: Boolean = false): Boolean {
    // 保存后的读取失败也按部分写入报告，保留备份路径。
    try {
        for (name in packageNames) {
            val content = readDebugConfigFile(Paths.appConfigFile(name))
            if (content == null && !shouldAllowAbsent) return false
            if (readDebugConfigFile(Paths.sharedAppConfigFile(name)) != content) return false
        }
    } catch (_: Exception) {
        return false
    }
    return true
}

internal suspend fun Context.saveDebugAppConfigs(command: String, target: String, targets: DebugConfigTargets, config: RedirectConfig): Bundle {
    val backupDir = backupDebugAppConfigs(command, targets.packageNames)
    val isSaved = targets.repository.saveAppConfigs(config, targets.packageNames)
    val isOk = isSaved && verifyDebugConfigMirrors(targets.packageNames)
    return debugConfigMutationResult(command, target, targets, config.getAppConfig(target), backupDir, isOk)
}

internal suspend fun Context.deleteDebugAppConfigs(command: String, target: String, targets: DebugConfigTargets): Bundle {
    require(targets.config.getAppConfig(target) != null) { "No config for $target" }
    val affectedPackages = targets.packageNames.filterTo(linkedSetOf()) { targets.config.getAppConfig(it) != null }
    val affectedTargets = targets.copy(packageNames = affectedPackages)
    val backupDir = requireNotNull(backupDebugAppConfigs(command, affectedPackages))
    val isSaved = targets.repository.deleteAppConfigs(targets.config.userId, affectedPackages, backupDir)
    val isOk = isSaved && verifyDebugConfigMirrors(affectedPackages, shouldAllowAbsent = true)
    return debugConfigMutationResult(command, target, affectedTargets, null, backupDir, isOk)
}

private fun debugConfigMutationResult(command: String, target: String, targets: DebugConfigTargets, config: AppRedirectConfig?, backupDir: String?, isOk: Boolean): Bundle = debugResult(
    isOk = isOk,
    message = "${if (isOk) "Saved" else "Save or shared mirror failed; changes may be partial"} user_id=${targets.config.userId} affected=${targets.packageNames.size} backup_dir=${backupDir ?: "none"}",
    command = command,
    target = target,
    value = Gson().toJson(mapOf("userId" to targets.config.userId, "affectedPackages" to targets.packageNames, "config" to config, "backupDir" to backupDir)),
)
