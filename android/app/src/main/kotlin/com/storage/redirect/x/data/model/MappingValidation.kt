package com.storage.redirect.x.data.model

enum class MappingProblem { MissingCapture, Cycle, TooDeep }

// 静态检查覆盖确定的前缀循环；原生层还会检查通配展开后的实际挂载图。
@android.annotation.SuppressLint("SdCardPath")
fun validateMappings(
    mappings: List<StoragePathMapping>,
    userId: Int = 0,
    packageName: String = "template",
): MappingProblem? {
    for (mapping in mappings) {
        if (mapping.finalPath.count { it == '*' || it == '?' } >
            mapping.requestPath.count { it == '*' || it == '?' }) return MappingProblem.MissingCapture
    }
    fun canonical(raw: String): String {
        val dollar = '$'
        val app = "/data/user/$userId/$packageName"
        val target = "/storage/emulated/$userId/Android/data/$packageName/sdcard"
        var path = raw.replace("{app_data_dir}", app).replace("{redirect_target}", target)
            .replace("${dollar}{APP_DATA_DIR}", app).replace("${dollar}APP_DATA_DIR", app)
            .replace("${dollar}{REDIRECT_TARGET}", target).replace("${dollar}REDIRECT_TARGET", target)
        for (alias in listOf("/sdcard", "/storage/self/primary", "/storage/emulated/0")) {
            if (path == alias || path.startsWith("$alias/")) {
                path = "/storage/emulated/$userId" + path.removePrefix(alias)
                break
            }
        }
        if (path.startsWith("/data/data/")) path = "/data/user/$userId/" + path.removePrefix("/data/data/")
        path = path.replace("/data/user/0/", "/data/user/$userId/")
        if (!path.startsWith('/')) path = "/storage/emulated/$userId/$path"
        return path.replace("*", "x").replace("?", "x").trimEnd('/')
    }
    val requests = mappings.map { canonical(it.requestPath) }
    val targets = mappings.map { canonical(it.finalPath) }
    val edges = targets.map { target -> requests.indices.filter { index ->
        val request = requests[index]
        target == request || target.startsWith("$request/") || request.startsWith("$target/")
    } }
    val visiting = BooleanArray(mappings.size)
    val heights = IntArray(mappings.size)
    fun visit(index: Int, depth: Int): MappingProblem? {
        if (visiting[index]) return MappingProblem.Cycle
        if (depth >= 10) return MappingProblem.TooDeep
        if (heights[index] != 0) return if (depth + heights[index] > 10) MappingProblem.TooDeep else null
        visiting[index] = true
        var height = 1
        for (child in edges[index]) {
            visit(child, depth + 1)?.let { return it }
            height = maxOf(height, heights[child] + 1)
        }
        visiting[index] = false
        heights[index] = height
        if (height + depth > 10) return MappingProblem.TooDeep
        return null
    }
    for (index in edges.indices) visit(index, 0)?.let { return it }
    return null
}
