package com.storage.redirect.x.debug

import android.content.Context
import android.os.Bundle
import com.storage.redirect.x.data.repository.SharedUidRepository
import kotlinx.coroutines.runBlocking

// 应用列表快照：包名|label|system|firstInstallTime，附带加载耗时
internal fun Context.runAppsListCommand(filter: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_APPS_LIST, filter) {
    val filterText = extras.optionalString(EXTRA_FILTER) ?: filter
    val limit = extras.optionalInt(EXTRA_LIMIT, 50).coerceIn(0, MAX_LIST_ROWS)
    val userId = extras.optionalInt(EXTRA_USER_ID, 0)

    val startedAt = System.currentTimeMillis()
    val apps = runBlocking {
        com.storage.redirect.x.data.provider.AppListProvider(this@runAppsListCommand)
            .getInstalledApps(userId, forceRefresh = true)
            .filter { filterText.isNullOrBlank() || it.packageName.contains(filterText, true) || it.appName.contains(filterText, true) }
            .map { AppRow(it.packageName, it.appName, it.isSystemApp, it.firstInstallTime) }
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
    val userId = android.os.Process.myUid() / 100000
    runBlocking { repo.update(userId, com.storage.redirect.x.data.provider.AppListProvider(this@runAppsSharedUidCommand).getInstalledApps(userId)) }
    val shared = repo.getSharedPackages(packageName, userId).let { if (it.isEmpty()) it else it + packageName }
    debugResult(
        isOk = true,
        message = if (shared.isEmpty()) "no shared uid" else "shared_uid_packages=${shared.size}",
        command = METHOD_APPS_SHARED_UID,
        target = packageName,
        value = shared.joinToString("\n"),
    )
}
