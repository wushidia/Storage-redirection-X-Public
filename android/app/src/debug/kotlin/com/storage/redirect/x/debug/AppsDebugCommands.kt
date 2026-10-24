package com.storage.redirect.x.debug

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.storage.redirect.x.data.repository.SharedUidRepository
import com.storage.redirect.x.data.service.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// 应用列表快照：包名|label|system|firstInstallTime，附带加载耗时
internal fun Context.runAppsListCommand(filter: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_APPS_LIST, filter) {
    val filterText = extras.optionalString(EXTRA_FILTER) ?: filter
    val limit = extras.optionalInt(EXTRA_LIMIT, 50).coerceIn(0, MAX_LIST_ROWS)
    val userId = extras.optionalInt(EXTRA_USER_ID, 0)

    val startedAt = System.currentTimeMillis()
    val apps = runBlocking {
        withContext(Dispatchers.IO) {
            val pm = packageManager
            // 非 0 用户走 pm 命令，多用户枚举只返回包名
            if (userId != 0) {
                listPackagesByPm(userId, filterText)
            } else {
                val flags = PackageManager.GET_META_DATA
                val enumerated = pm.getInstalledApplications(flags)
                    .filter { it.packageName != packageName }
                    .filter { info ->
                        filterText.isNullOrBlank() ||
                            info.packageName.contains(filterText, ignoreCase = true) ||
                            info.loadLabel(pm).toString().contains(filterText, ignoreCase = true)
                    }
                    .map { info ->
                        val installTime = try {
                            pm.getPackageInfo(info.packageName, 0).firstInstallTime
                        } catch (_: Exception) {
                            0L
                        }
                        AppRow(
                            packageName = info.packageName,
                            label = info.loadLabel(pm).toString(),
                            isSystemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                            firstInstallTime = installTime,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                // MIUI 拦截 debuggable 包的枚举，回退 root 的 pm 命令
                if (enumerated.isEmpty()) listPackagesByPm(0, filterText) else enumerated
            }
        }
    }
    val elapsed = System.currentTimeMillis() - startedAt

    val matched = apps.size
    val rows = if (limit > 0) apps.take(limit) else apps.take(MAX_LIST_ROWS)
    debugResult(
        isOk = true,
        message = "matched=$matched rows=${rows.size} elapsed_ms=$elapsed user_id=$userId",
        command = METHOD_APPS_LIST,
        target = filterText,
        value = rows.joinToString("\n") { "${it.packageName}|${it.label}|${it.isSystemApp}|${it.firstInstallTime}" },
    )
}

private data class AppRow(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val firstInstallTime: Long,
)

// root 枚举：pm list packages -f 输出 package:<apk 路径>=<包名>
private suspend fun Context.listPackagesByPm(userId: Int, filterText: String?): List<AppRow> {
    val result = RootService.runCommand("pm list packages -f --user $userId")
    if (!result.isSuccess) return emptyList()
    return result.stdout.lineSequence()
        .mapNotNull { line ->
            val pkg = line.substringAfterLast('=').trim()
            val apkPath = line.removePrefix("package:").substringBeforeLast('=').trim()
            if (pkg.isEmpty() || pkg == packageName) return@mapNotNull null
            if (!filterText.isNullOrBlank() && !pkg.contains(filterText, ignoreCase = true)) return@mapNotNull null
            AppRow(
                packageName = pkg,
                label = pkg,
                isSystemApp = apkPath.startsWith("/system") ||
                    apkPath.startsWith("/product") ||
                    apkPath.startsWith("/vendor"),
                firstInstallTime = 0L,
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

// 查询与指定包共享 UID 的包名列表
internal fun Context.runAppsSharedUidCommand(packageName: String?): Bundle = runDebugCommand(METHOD_APPS_SHARED_UID, packageName) {
    if (packageName.isNullOrBlank()) {
        return@runDebugCommand debugResult(
            isOk = false,
            message = "Missing package name",
            command = METHOD_APPS_SHARED_UID,
        )
    }
    val repo = SharedUidRepository()
    val loaded = runBlocking { repo.load() }
    if (!loaded) {
        return@runDebugCommand debugResult(
            isOk = false,
            message = "Shared UID map unavailable (root required)",
            command = METHOD_APPS_SHARED_UID,
            target = packageName,
        )
    }
    val shared = repo.getAllSharedPackages(packageName)
    debugResult(
        isOk = true,
        message = if (shared.isEmpty()) "no shared uid" else "shared_uid_packages=${shared.size}",
        command = METHOD_APPS_SHARED_UID,
        target = packageName,
        value = shared.joinToString("\n"),
    )
}
