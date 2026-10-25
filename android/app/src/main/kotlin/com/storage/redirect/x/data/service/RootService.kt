package com.storage.redirect.x.data.service

import android.util.Base64
import com.storage.redirect.x.BuildConfig
import com.storage.redirect.x.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.IOException

// Root 命令执行结果
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

// 系统用户信息
data class SystemUser(
    val id: Int,
    val name: String
)

// Root 命令执行服务（直接调用 su，无需 MethodChannel）
// 无状态单例，避免各页面重复创建实例
object RootService {
    private val commandRunner = RootCommandRunner()
    private lateinit var applicationApk: String

    fun initialize(apk: String) { applicationApk = apk }

    suspend fun packageCommand(vararg arguments: String): CommandResult = runCommand(
        "CLASSPATH=${shellQuote(applicationApk)} /system/bin/app_process /system/bin " +
            "org.srx.packages.PackageQueryMain " + arguments.joinToString(" ", transform = ::shellQuote)
    )

    suspend fun isRootAvailable(): Boolean {
        val result = runCommand("id")
        val isAvailable = result.isSuccess && result.stdout.contains("uid=0")
        Logger.debug("Root check: available=$isAvailable")
        return isAvailable
    }

    // MediaProvider 一重启，正在运行的重定向 app 里的 FUSE 就失效了，得让它们冷启动重建挂载
    suspend fun restartMediaProvider(redirectApps: List<String> = emptyList()): Boolean {
        val runningApps = filterRunningApps(redirectApps.filter { isSafePackageName(it) })

        val mediaStop = listOf(
            "am force-stop com.google.android.providers.media.module 2>/dev/null",
            "am force-stop com.android.providers.media.module 2>/dev/null",
            "am force-stop com.android.providers.media 2>/dev/null",
        ).joinToString(" ; ")

        // 查询会把新的 MediaProvider 唤起来；丢后台跟杀 app 并行
        val warmup = "( content query --uri content://media/external/file " +
            "--projection _id --where '1=0' >/dev/null 2>&1 ) &"

        val appStops = runningApps
            .joinToString(" ; ") { "am force-stop ${shellQuote(it)} 2>/dev/null" }

        val command = buildString {
            append(mediaStop)
            append(" ; ")
            append(warmup)
            if (appStops.isNotEmpty()) {
                append(' ')
                append(appStops)
                append(" ;")
            }
            append(" wait")
        }

        val result = runCommand(command)
        if (!result.isSuccess) {
            Logger.warn("restart MediaProvider failed: exit=${result.exitCode} stderr=${result.stderr}")
        } else {
            Logger.info("restart MediaProvider done, killed apps=${runningApps.size}")
        }
        return result.isSuccess
    }

    private suspend fun filterRunningApps(packages: List<String>): List<String> {
        if (packages.isEmpty()) return emptyList()
        val result = runCommand("ps -A -o NAME 2>/dev/null")
        if (!result.isSuccess) return packages  // 查不到就保守地全算上
        val running = result.stdout.lineSequence()
            .map { it.trim().substringBefore(':') }  // 进程名可能带 :子进程，取主包名
            .toHashSet()
        return packages.filter { it in running }
    }

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.isNotEmpty() &&
            packageName != BuildConfig.APPLICATION_ID &&
            packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun runCommand(command: String): CommandResult = execute(command)

    suspend fun runCommandStreaming(
        command: String, timeoutMs: Long = 120_000, onStdoutLine: (String) -> Unit,
    ): CommandResult = execute(command, timeoutMs, onStdoutLine)

    private suspend fun execute(
        command: String, timeoutMs: Long = 120_000, onStdoutLine: ((String) -> Unit)? = null,
    ): CommandResult {
        try {
            val result = commandRunner.run(command, timeoutMs, onStdoutLine)
            if (!result.isSuccess) {
                Logger.warn("Command failed [${result.exitCode}]: $command | stderr=${result.stderr}")
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.error("Command error: $command | ${e.message}")
            return CommandResult(-1, "", e.message.orEmpty())
        }
    }

    suspend fun readFile(path: String): String? {
        val result = runCommand("cat '$path' 2>/dev/null")
        if (!result.isSuccess) Logger.warn("Read file failed: $path")
        return if (result.isSuccess) result.stdout else null
    }

    // 写入文件：使用 base64 编码避免 shell 特殊字符问题
    suspend fun writeFile(path: String, content: String): Boolean {
        val encoded = Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val result = runCommand("echo '$encoded' | base64 -d > '$path'")
        if (!result.isSuccess) Logger.error("Write file failed: $path")
        return result.isSuccess
    }

}
