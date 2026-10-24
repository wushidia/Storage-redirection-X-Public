package com.storage.redirect.x.data.repository

import android.annotation.SuppressLint
import com.storage.redirect.x.data.model.PathValidationResult
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.data.service.CommandResult

// 目录浏览数据源，读取当前用户存储目录并转为相对路径
class PathBrowserRepository(
    private val runCommand: suspend (String) -> CommandResult = RootService::runCommand,
) {

    // 列出相对目录，默认根目录为 /storage/emulated/{userId}
    suspend fun listRelativeDirectories(userId: Int, relativePath: String): List<String>? {
        val normalized = normalizeRelativePath(relativePath) ?: return null
        val baseDir = "/storage/emulated/$userId"
        val currentDir = if (normalized.isEmpty()) baseDir else "$baseDir/$normalized"

        // -A 列出点前缀目录；仅列目录并保留一层名称，避免文件与额外元信息干扰
        val command = "ls -1 -A -p ${quotePath(currentDir)} 2>/dev/null"
        val result = runCommand(command)
        if (!result.isSuccess) {
            return null
        }

        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.endsWith("/") }
            .map { it.removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .mapNotNull { child ->
                val candidate = if (normalized.isEmpty()) child else "$normalized/$child"
                RedirectConfig.normalizeAllowedPath(candidate)
            }
            .sorted()
            .toList()
    }

    // 与模块挂载源使用同一个后端目录，避免经 FUSE 读取 Android/data。
    @SuppressLint("SdCardPath")
    suspend fun listAppPrivateDirectories(
        userId: Int,
        packageName: String,
        relativePath: String
    ): List<String>? {
        val normalized = normalizeSimpleRelativePath(relativePath) ?: return null
        val baseDir = "/data/media/$userId/Android/data/$packageName/sdcard"
        val currentDir = if (normalized.isEmpty()) baseDir else "$baseDir/$normalized"

        // 尚未运行过的重定向应用可能还没有隔离根目录，不在浏览时创建它。
        val command = if (normalized.isEmpty()) {
            "if [ ! -e ${quotePath(baseDir)} ]; then exit 0; fi; " +
                "ls -1 -A -p ${quotePath(currentDir)} 2>/dev/null"
        } else {
            "ls -1 -A -p ${quotePath(currentDir)} 2>/dev/null"
        }
        val result = runCommand(command)
        if (!result.isSuccess) return null

        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.endsWith("/") }
            .map { it.removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .map { child ->
                if (normalized.isEmpty()) child else "$normalized/$child"
            }
            .sorted()
            .toList()
    }

    private fun normalizeRelativePath(raw: String): String? {
        if (raw.isBlank()) return ""
        val validated = RedirectConfig.validateAllowedPath(raw)
        return when (validated) {
            is PathValidationResult.Valid -> validated.normalized
            else -> null
        }
    }

    private fun quotePath(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    // 私有路径仅拒绝独立的遍历段，不误拒绝名称中的连续点。
    private fun normalizeSimpleRelativePath(raw: String): String? {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.startsWith("/") || trimmed.split('/').any { it == "." || it == ".." }) return null
        return trimmed
    }
}
