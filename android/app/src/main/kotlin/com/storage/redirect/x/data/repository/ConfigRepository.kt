package com.storage.redirect.x.data.repository

import android.util.Base64
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.AppRedirectConfig
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.model.validateMappings
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Logger
import com.storage.redirect.x.util.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val FILE_DUMP_MARKER = "@@SRX@@"

data class AutoRedirectPolicy(val isEnabled: Boolean = false, val templateId: String = "")

// 配置仓库：读取/写入全局配置和应用配置
class ConfigRepository {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // 加载完整配置
    suspend fun load(userId: Int): RedirectConfig {
        Logger.debug("Load config: userId=$userId")
        val globalConfig = readGlobalConfig()
        val appConfigs = readAllAppConfigs(userId)
        val redirectApps = appConfigs.filter { it.isEnabled }.map { it.packageName }

        Logger.debug("Load config done: redirected=${redirectApps.size}, total=${appConfigs.size}")
        return RedirectConfig(
            userId = userId,
            isFileMonitorEnabled = globalConfig.isFileMonitorEnabled,
            isFuseFixerEnabled = globalConfig.isFuseFixerEnabled,
            redirectApps = redirectApps,
            appConfigs = appConfigs,
        )
    }

    suspend fun loadPackages(userId: Int, packageNames: Collection<String>): RedirectConfig {
        val targetPackages = packageNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        Logger.debug("Load partial config: userId=$userId, appCount=${targetPackages.size}")
        val globalConfig = readGlobalConfig()
        val appConfigs = targetPackages.mapNotNull { readAppConfig(it, userId) }
        val redirectApps = appConfigs.filter { it.isEnabled }.map { it.packageName }
        return RedirectConfig(
            userId = userId,
            isFileMonitorEnabled = globalConfig.isFileMonitorEnabled,
            isFuseFixerEnabled = globalConfig.isFuseFixerEnabled,
            redirectApps = redirectApps,
            appConfigs = appConfigs,
        )
    }

    // 保存完整配置
    suspend fun save(config: RedirectConfig): Boolean = withContext(Dispatchers.IO) {
        Logger.debug("Save config: userId=${config.userId}, appCount=${config.appConfigs.size}")
        if (!writeGlobalConfig(config.isFileMonitorEnabled, config.isFuseFixerEnabled)) {
            Logger.error("Save global config failed")
            return@withContext false
        }
        if (!ensureAppsConfigDir()) {
            Logger.error("Prepare app config directory failed")
            return@withContext false
        }

        for (appConfig in config.appConfigs) {
            if (!writeAppConfigFile(appConfig, config.userId)) {
                Logger.error("Save app config failed: ${appConfig.packageName}")
                return@withContext false
            }
        }
        Logger.debug("Config saved")
        notifyRulesReload(config.userId)
    }

    // 仅保存受影响的应用配置，避免小改动触发整份配置重写
    suspend fun saveAppConfigs(config: RedirectConfig, packageNames: Collection<String>): Boolean =
        withContext(Dispatchers.IO) {
            val targetPackages = packageNames.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSortedSet()
            Logger.debug("Save partial app configs: userId=${config.userId}, appCount=${targetPackages.size}")
            if (targetPackages.isEmpty()) {
                return@withContext true
            }
            if (!ensureAppsConfigDir()) {
                Logger.error("Prepare app config directory failed")
                return@withContext false
            }

            for (packageName in targetPackages) {
                val appConfig = config.getAppConfig(packageName)
                if (appConfig == null) {
                    Logger.debug("App config missing, skip saving: $packageName")
                    continue
                }
                if (!writeAppConfigFile(appConfig, config.userId)) {
                    Logger.error("Save app config failed: $packageName")
                    return@withContext false
                }
            }
            Logger.debug("Partial app configs saved")
            notifyRulesReload(config.userId)
        }

    suspend fun setFuseFixerEnabled(isEnabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            writeGlobalConfig(isFuseFixerEnabled = isEnabled)
        }

    suspend fun readFuseFixerEnabled(): Boolean = readGlobalConfig().isFuseFixerEnabled

    suspend fun readAutoRedirectPolicy(): AutoRedirectPolicy = readGlobalConfig().autoRedirectPolicy

    suspend fun setAutoRedirectPolicy(policy: AutoRedirectPolicy): Boolean = withContext(Dispatchers.IO) {
        if (policy.isEnabled && !TemplateRepository().syncToModule()) return@withContext false
        writeGlobalConfig(autoRedirectPolicy = policy)
    }

    // 保留其他用户；最后一个用户删除后，将文件移入备份目录。
    suspend fun deleteAppConfigs(userId: Int, packageNames: Collection<String>, backupDir: String): Boolean =
        withContext(Dispatchers.IO) {
            for (packageName in packageNames.toSortedSet()) {
                val result = RootService.packageCommand("remove-profile", Paths.CONFIG_DIR, packageName,
                    userId.toString(), backupDir)
                if (!result.isSuccess) return@withContext false
            }
            notifyRulesReload(userId)
        }

    suspend fun readFileMonitorEnabled(): Boolean = readGlobalConfig().isFileMonitorEnabled.also {
        publishMonitorEnabled(it)
    }

    suspend fun setFileMonitorEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        writeGlobalConfig(isFileMonitorEnabled = enabled)
    }

    suspend fun readFileMonitorRetention(): Long = readGlobalConfig().fileMonitorRetentionSeconds

    suspend fun setFileMonitorRetention(seconds: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (seconds !in MONITOR_RETENTION_OPTIONS) return@withContext false
            writeGlobalConfig(retentionSeconds = seconds)
        }

    // 读取全局配置，字段缺失时走保守默认
    private suspend fun readGlobalConfig(): GlobalConfig {
        val content = RootService.readFile(Paths.GLOBAL_CONFIG_FILE)
        if (content.isNullOrBlank()) return GlobalConfig()
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            GlobalConfig(
                isFileMonitorEnabled = json.get("file_monitor_enabled")?.asBoolean ?: true,
                isFuseFixerEnabled = json.get("fuse_fixer_enabled")?.asBoolean ?: false,
                fileMonitorRetentionSeconds =
                    json.get("file_monitor_retention_seconds")?.asLong?.takeIf { it >= 0 }
                        ?: 604800L,
                autoRedirectPolicy = AutoRedirectPolicy(
                    json.get("auto_enable_redirect_for_new_apps")?.asBoolean ?: false,
                    json.get("auto_enable_new_apps_template_id")?.asString.orEmpty(),
                ),
            )
        } catch (_: Exception) {
            GlobalConfig()
        }
    }

    private suspend fun readAllAppConfigs(userId: Int): List<AppRedirectConfig> {
        // 一次 awk 遍历全部配置，空目录不触发读取错误
        val result = RootService.runCommand(
            "set -- ${Paths.APPS_CONFIG_DIR}/*.json; [ -f \"\$1\" ] || exit 0; " +
                "awk 'FNR==1{print \"$FILE_DUMP_MARKER\" FILENAME}{print}' \"\$@\""
        )
        if (!result.isSuccess || result.stdout.isBlank()) return emptyList()
        return parseAppConfigDump(result.stdout, userId)
    }

    // 解析 awk 输出：@@SRX@@<路径> 行作条目分隔，其余为 json 原文
    private fun parseAppConfigDump(stdout: String, userId: Int): List<AppRedirectConfig> {
        val appConfigs = ArrayList<AppRedirectConfig>()
        val content = StringBuilder()
        var packageName: String? = null
        fun flush() {
            val name = packageName ?: return
            parseAppConfigContent(name, content.toString(), userId)?.let { appConfigs.add(it) }
            content.clear()
        }
        for (line in stdout.lineSequence()) {
            if (line.startsWith(FILE_DUMP_MARKER)) {
                flush()
                val fileName = line.removePrefix(FILE_DUMP_MARKER).substringAfterLast('/')
                packageName = fileName.removeSuffix(".json")
            } else {
                content.appendLine(line)
            }
        }
        flush()
        return appConfigs.sortedBy { it.packageName }
    }

    // 读取单个应用配置
    private suspend fun readAppConfig(packageName: String, userId: Int): AppRedirectConfig? {
        val path = Paths.appConfigFile(packageName)
        val content = RootService.readFile(path) ?: return null
        return parseAppConfigContent(packageName, content, userId)
    }

    private fun parseAppConfigContent(packageName: String, content: String, userId: Int): AppRedirectConfig? {
        if (content.isBlank()) return null
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            val users = json.getAsJsonObject("users") ?: return null
            val userObj = users.getAsJsonObject(userId.toString()) ?: return null
            AppRedirectConfig.fromUserJson(packageName, userObj)
        } catch (e: Exception) {
            Logger.error("Parse app config failed: $packageName", e)
            null
        }
    }

    // 写入全局配置
    private suspend fun writeGlobalConfig(
        isFileMonitorEnabled: Boolean? = null,
        isFuseFixerEnabled: Boolean? = null,
        retentionSeconds: Long? = null,
        autoRedirectPolicy: AutoRedirectPolicy? = null,
    ): Boolean {
        return globalWriteMutex.withLock {
            if (!ensureConfigDir()) {
                return@withLock false
            }
            val existing = RootService.readFile(Paths.GLOBAL_CONFIG_FILE)
            val globalJson =
                updateGlobalConfig(
                    existing,
                    isFileMonitorEnabled,
                    isFuseFixerEnabled,
                    retentionSeconds,
                )
            if (autoRedirectPolicy != null) {
                globalJson.addProperty("auto_enable_redirect_for_new_apps", autoRedirectPolicy.isEnabled)
                globalJson.addProperty("auto_enable_new_apps_template_id", autoRedirectPolicy.templateId)
            }
            val content = gson.toJson(globalJson) + "\n"
            val temporary = "${Paths.GLOBAL_CONFIG_FILE}.tmp"
            val isSaved =
                RootService.writeFile(temporary, content) &&
                    RootService.runCommand(
                            "chmod 644 '$temporary' && mv -f '$temporary' '${Paths.GLOBAL_CONFIG_FILE}'"
                        )
                        .isSuccess
            if (isSaved) {
                mirrorSharedFile(Paths.SHARED_CONFIG_DIR, Paths.SHARED_GLOBAL_CONFIG_FILE, content)
                if (isFileMonitorEnabled != null) {
                    val applied = notifyPolicyReload()
                    if (!applied) return@withLock false
                    publishMonitorEnabled(globalJson.get("file_monitor_enabled").asBoolean)
                }
            }
            isSaved
        }
    }

    private suspend fun notifyPolicyReload(): Boolean = RootService.runCommand(
        "for b in ${Paths.MODULE_DIR}/bin/*/srx_logd; do [ -x \"\$b\" ] || continue; \"\$b\" control monitor-policy && exit 0; done; exit 1"
    ).isSuccess

    private suspend fun notifyRulesReload(userId: Int): Boolean {
        val isReloaded = RootService.runCommand(
            "for b in ${Paths.MODULE_DIR}/bin/*/srx_logd; do [ -x \"\$b\" ] || continue; \"\$b\" control rules:$userId && exit 0; done; exit 1"
        ).isSuccess
        if (!isReloaded) return false
        return RootService.packageCommand(
            "sync-config-runtime", Paths.CONFIG_DIR, "/dev/srx_runtime", "apply"
        ).isSuccess
    }

    // 确保配置目录存在
    private suspend fun ensureConfigDir(): Boolean {
        val result = RootService.runCommand("mkdir -p ${Paths.CONFIG_DIR}")
        return result.isSuccess
    }

    // 确保应用配置目录存在
    private suspend fun ensureAppsConfigDir(): Boolean {
        val result = RootService.runCommand("mkdir -p ${Paths.APPS_CONFIG_DIR}")
        return result.isSuccess
    }

    // 写入单个应用配置，保留其他用户的配置
    private suspend fun writeAppConfigFile(config: AppRedirectConfig, userId: Int): Boolean {
        if (validateMappings(config.pathMappings, userId, config.packageName) != null) {
            Logger.warn("Mapping graph rejected for ${config.packageName}")
            return false
        }
        val profile = Base64.encodeToString(config.toUserJson().toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val result = RootService.packageCommand("write-profile", Paths.CONFIG_DIR, config.packageName,
            userId.toString(), profile)
        return result.isSuccess
    }

    private suspend fun mirrorSharedFile(
        parentDir: String,
        path: String,
        content: String,
    ): Boolean {
        RootService.runCommand(
            "mkdir -p '$parentDir' && chmod 755 '${Paths.SHARED_CONFIG_DIR}' '$parentDir'"
        )
        val temporary = "$path.tmp"
        val isSaved =
            RootService.writeFile(temporary, content) &&
                RootService.runCommand(
                        "chmod 644 '$temporary' && (chcon u:object_r:shell_data_file:s0 '$temporary' 2>/dev/null || true) && mv -f '$temporary' '$path'"
                    )
                    .isSuccess
        if (!isSaved) Logger.warn("Mirror shared config failed: $path")

        return isSaved
    }

    private data class GlobalConfig(
        val isFileMonitorEnabled: Boolean = true,
        val isFuseFixerEnabled: Boolean = false,
        val fileMonitorRetentionSeconds: Long = 604800L,
        val autoRedirectPolicy: AutoRedirectPolicy = AutoRedirectPolicy(),
    )

    companion object {
        private val monitorEnabledState = MutableStateFlow<Boolean?>(null)
        val monitorEnabled = monitorEnabledState.asStateFlow()
        fun publishMonitorEnabled(enabled: Boolean) { monitorEnabledState.value = enabled }


        internal fun updateGlobalConfig(
            existing: String?,
            monitor: Boolean? = null,
            fuse: Boolean? = null,
            retention: Long? = null,
        ): JsonObject {
            val json =
                runCatching { JsonParser.parseString(existing).asJsonObject }.getOrNull()
                    ?: JsonObject()
            if (monitor != null || !json.has("file_monitor_enabled"))
                json.addProperty("file_monitor_enabled", monitor ?: true)
            if (fuse != null || !json.has("fuse_fixer_enabled"))
                json.addProperty("fuse_fixer_enabled", fuse ?: false)
            if (retention != null) json.addProperty("file_monitor_retention_seconds", retention)
            return json
        }

        private val globalWriteMutex = Mutex()
        val MONITOR_RETENTION_OPTIONS =
            listOf(3600L, 21600L, 86400L, 259200L, 604800L, 2592000L, 0L)
    }
}
