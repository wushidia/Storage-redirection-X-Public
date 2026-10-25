package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.model.AppInfo

// 共享 UID 必须来自相同 Android 用户的 PM 查询结果。
class SharedUidRepository {
    private val groupsByUser = mutableMapOf<Int, Map<String, List<String>>>()

    fun update(userId: Int, apps: List<AppInfo>) {
        val groups = apps.filter { it.uid >= 0 && it.userId == userId }
            .groupBy { it.uid }.values.filter { it.size > 1 }
        groupsByUser[userId] = buildMap {
            for (group in groups) {
                val names = group.map { it.packageName }.sorted()
                names.forEach { put(it, names) }
            }
        }
    }

    fun getSharedPackages(packageName: String, userId: Int): List<String> =
        groupsByUser[userId]?.get(packageName).orEmpty().filter { it != packageName }

    fun allSharedUidPackages(userId: Int): Set<String> = groupsByUser[userId]?.keys.orEmpty()
}
