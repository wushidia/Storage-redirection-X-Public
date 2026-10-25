package com.storage.redirect.x.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// 应用重定向配置
data class AppRedirectConfig(
    val packageName: String,
    val isEnabled: Boolean = true,
    val allowedRealPaths: List<String> = emptyList(),
    val pathMappings: List<StoragePathMapping> = emptyList(),
    val isMappingModeOnly: Boolean = false,
    val sandboxedPaths: List<String> = emptyList(),
    val readOnlyPaths: List<String> = emptyList(),
) {
    val isActive: Boolean get() = isEnabled

    // 序列化为 JSON（用于写入用户维度配置）
    fun toUserJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("enabled", isEnabled)
        if (isMappingModeOnly) obj.addProperty("mapping_mode_only", true)
        if (sandboxedPaths.isNotEmpty()) {
            obj.add("sandboxed_paths", JsonArray().apply { sandboxedPaths.forEach { add(it) } })
        }
        if (readOnlyPaths.isNotEmpty()) {
            obj.add("read_only_paths", JsonArray().apply { readOnlyPaths.forEach { add(it) } })
        }
        if (allowedRealPaths.isNotEmpty()) {
            val arr = JsonArray()
            allowedRealPaths.forEach { arr.add(it) }
            obj.add("allowed_real_paths", arr)
        }
        if (pathMappings.isNotEmpty()) {
            val map = JsonObject()
            pathMappings.forEach { map.addProperty(it.requestPath, it.finalPath) }
            obj.add("path_mappings", map)
        }
        return obj
    }

    companion object {
        // 从用户维度 JSON 解析
        fun fromUserJson(packageName: String, json: JsonObject): AppRedirectConfig {
            val isEnabled = json.get("enabled")?.asBoolean ?: true

            val allowedRealPaths = json.getAsJsonArray("allowed_real_paths")
                ?.mapNotNull { it.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: emptyList()

            val mappings = mutableListOf<StoragePathMapping>()
            val mappingsObj = json.get("path_mappings")
            if (mappingsObj != null && mappingsObj.isJsonObject) {
                mappingsObj.asJsonObject.entrySet().forEach { (key, value) ->
                    val requestPath = key.trim()
                    val finalPath = value.asString?.trim() ?: return@forEach
                    if (requestPath.isNotEmpty() && finalPath.isNotEmpty()) {
                        mappings.add(StoragePathMapping(requestPath, finalPath))
                    }
                }
            }

            return AppRedirectConfig(
                packageName = packageName,
                isEnabled = isEnabled,
                allowedRealPaths = allowedRealPaths,
                pathMappings = mappings.sortedBy { it.requestPath },
                isMappingModeOnly = json.get("mapping_mode_only")?.asBoolean ?: false,
                sandboxedPaths = readPaths(json, "sandboxed_paths"),
                readOnlyPaths = readPaths(json, "read_only_paths"),
            )
        }

        private fun readPaths(json: JsonObject, name: String): List<String> =
            json.getAsJsonArray(name)?.map { it.asString.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }
}
