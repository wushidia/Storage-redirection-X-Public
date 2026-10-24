package com.storage.redirect.x.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.storage.redirect.x.data.cache.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Debug 包专用的指令入口，仅授权 shell / root / 自身进程调用
class DebugCommandProvider : ContentProvider() {

    private val commandMutex = Mutex()

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        if (!isAuthorizedCaller()) {
            return debugResult(isOk = false, message = "Unauthorized caller", command = method, target = arg)
        }
        val context = context
            ?: return debugResult(isOk = false, message = "Context is not ready", command = method, target = arg)
        // provider 进程可能先于 MainActivity 初始化，确保 prefs 可用（init 幂等）
        AppPreferences.init(context)

        return runDebugCommand(method, arg) {
            val spec = debugCommandSpecs.firstOrNull { it.method == method }
                ?: return@runDebugCommand debugResult(isOk = false, message = "Unknown method: $method; use commands.help", command = method, target = arg)
            require(extras?.keySet().orEmpty().all { it in spec.extras }) { "Unknown extras; use commands.help --arg $method" }
            runBlocking(Dispatchers.IO) {
                commandMutex.withLock { dispatchCommand(context, method, arg, extras) }
            }
        }
    }

    private suspend fun dispatchCommand(context: Context, method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        "commands.help" -> runCommandsHelpCommand(arg)
        METHOD_PAGE_OPEN -> context.openDebugPage(arg)
        METHOD_APPS_LIST -> context.runAppsListCommand(arg, extras)
        METHOD_APPS_SHARED_UID -> context.runAppsSharedUidCommand(arg)
        METHOD_BROWSER_LIST -> context.runBrowserListCommand(arg, extras)
        METHOD_MONITOR_RECENT -> context.runMonitorRecentCommand(extras)
        METHOD_CONFIG_GET -> context.runConfigGetCommand(arg, extras)
        METHOD_CONFIG_LIST -> context.runConfigListCommand(arg, extras)
        METHOD_CONFIG_SET, METHOD_CONFIG_DELETE, METHOD_CONFIG_CLEAR,
        METHOD_CONFIG_SET_ENABLED, METHOD_CONFIG_ALLOWED_ADD, METHOD_CONFIG_ALLOWED_REMOVE,
        METHOD_CONFIG_MAPPING_SET, METHOD_CONFIG_MAPPING_REMOVE -> context.runConfigMutationCommand(method, arg, extras)
        METHOD_TEMPLATES_LIST -> context.runTemplatesListCommand(extras)
        "templates.get" -> context.runTemplatesGetCommand(arg)
        "templates.save" -> context.runTemplatesSaveCommand(arg, extras)
        "templates.delete" -> context.runTemplatesDeleteCommand(arg)
        "templates.from_app" -> context.runTemplatesFromAppCommand(arg, extras)
        "templates.apply" -> context.runTemplatesApplyCommand(arg, extras)
        else -> debugResult(isOk = false, message = "Unknown method: $method", command = method, target = arg)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun isAuthorizedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        return callingUid == Process.SHELL_UID ||
            callingUid == Process.ROOT_UID ||
            callingUid == Process.myUid()
    }
}
