package com.storage.redirect.x.debug

import android.content.Context
import android.os.Bundle
import com.storage.redirect.x.data.repository.PathBrowserRepository
import kotlinx.coroutines.runBlocking

// 列出共享存储下的子目录，arg 为相对路径（空 = 根目录）
internal fun Context.runBrowserListCommand(relativePath: String?, extras: Bundle?): Bundle = runDebugCommand(METHOD_BROWSER_LIST, relativePath) {
    val userId = extras.optionalInt(EXTRA_USER_ID, 0)
    val dirs = runBlocking {
        PathBrowserRepository().listRelativeDirectories(userId, relativePath.orEmpty())
    } ?: return@runDebugCommand debugResult(
        isOk = false,
        message = "Invalid path: $relativePath",
        command = METHOD_BROWSER_LIST,
        target = relativePath,
    )

    val limit = extras.optionalInt(EXTRA_LIMIT, 0).coerceIn(0, MAX_LIST_ROWS)
    val rows = if (limit > 0) dirs.take(limit) else dirs.take(MAX_LIST_ROWS)
    debugResult(
        isOk = true,
        message = "matched=${dirs.size} rows=${rows.size} user_id=$userId",
        command = METHOD_BROWSER_LIST,
        target = relativePath,
        value = rows.joinToString("\n"),
    )
}
