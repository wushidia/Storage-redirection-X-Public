package com.storage.redirect.x.data.provider

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.AppInfo
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.data.service.SystemUser
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

interface AppCatalogSource {
    suspend fun getInstalledApps(userId: Int, forceRefresh: Boolean = false): ImmutableList<AppInfo>
    suspend fun getUsers(): ImmutableList<SystemUser>
    suspend fun getIcon(packageName: String, userId: Int): Drawable?
    suspend fun getInstallTimes(apps: ImmutableList<AppInfo>): ImmutableList<AppInfo>
}

class AppListProvider(context: Context) : AppCatalogSource {
    private val context = context.applicationContext
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(2)
    private data class Entry(val application: ApplicationInfo, val installedAt: Long)

    override suspend fun getInstalledApps(userId: Int, forceRefresh: Boolean): ImmutableList<AppInfo> =
        withContext(Dispatchers.IO) {
            val key = context.applicationInfo.sourceDir to userId
            val entries = if (!forceRefresh) catalogs[key] else null
            val installed = entries ?: readPackages(userId).also { catalogs[key] = it }
            val pm = context.packageManager
            installed.values.map { entry ->
                val app = entry.application
                AppInfo(
                    packageName = app.packageName,
                    appName = app.loadLabel(pm).toString(),
                    isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    firstInstallTime = entry.installedAt,
                    userId = userId,
                    uid = app.uid,
                )
            }.sortedBy { it.appName.lowercase() }.toPersistentList()
        }

    private suspend fun readPackages(userId: Int): Map<String, Entry> {
        val result = RootService.packageCommand("packages", userId.toString())
        if (!result.isSuccess) throw IOException(result.stderr.trim())
        return JsonParser.parseString(result.stdout).asJsonArray.mapNotNull { element ->
            val json = element.asJsonObject
            val parcel = Parcel.obtain()
            val app = try {
                val bytes = Base64.decode(json.get("application").asString, Base64.NO_WRAP)
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                ApplicationInfo.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
            if (app.packageName == context.packageName) null
            else app.packageName to Entry(app, json.get("installed_at").asLong)
        }.toMap()
    }

    override suspend fun getUsers(): ImmutableList<SystemUser> = withContext(Dispatchers.IO) {
        val result = RootService.packageCommand("users")
        if (!result.isSuccess) throw IOException(result.stderr.trim())
        JsonParser.parseString(result.stdout).asJsonArray.map {
            val user = it.asJsonObject
            SystemUser(user.get("id").asInt, user.get("name").asString)
        }.sortedBy { it.id }.toPersistentList()
    }

    override suspend fun getIcon(packageName: String, userId: Int): Drawable? =
        withContext(iconDispatcher) {
            val app = catalogs[context.applicationInfo.sourceDir to userId]
                ?.get(packageName)?.application ?: return@withContext null
            try {
                app.loadIcon(context.packageManager).toBitmap(96, 96).toDrawable(context.resources)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

    override suspend fun getInstallTimes(apps: ImmutableList<AppInfo>): ImmutableList<AppInfo> = apps

    companion object {
        private val catalogs = ConcurrentHashMap<Pair<String, Int>, Map<String, Entry>>()
    }
}
