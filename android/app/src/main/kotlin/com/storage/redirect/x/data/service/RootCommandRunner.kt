package com.storage.redirect.x.data.service

import java.io.InputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class RootCommandRunner(
    private val startProcess: (String) -> Process = { ProcessBuilder("su", "-c", it).start() },
) {
    suspend fun run(
        command: String,
        timeoutMs: Long = 120_000,
        onStdoutLine: ((String) -> Unit)? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = startProcess(command)
        process.outputStream.close()
        val stdout = readStream("srx-root-stdout", process.inputStream, onStdoutLine)
        val stderr = readStream("srx-root-stderr", process.errorStream, null)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException()
                if (process.waitFor(remaining.coerceAtMost(100_000_000), TimeUnit.NANOSECONDS)) break
                if (stdout.isDone) stdout.get()
                if (stderr.isDone) stderr.get()
            }
            CommandResult(process.exitValue(),
                stdout.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS),
                stderr.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
        } catch (_: TimeoutException) {
            CommandResult(124, "", "命令执行超时")
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is CancellationException) throw cause
            throw IOException("读取 Root 命令输出失败", cause ?: error)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    // 两个管道必须同时排空，否则 stderr 写满时子进程无法关闭 stdout。
    private fun readStream(name: String, stream: InputStream, onLine: ((String) -> Unit)?): FutureTask<String> {
        val task = FutureTask(Callable {
            stream.bufferedReader().use { reader ->
                if (onLine == null) reader.readText()
                else buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                        append(line).append('\n')
                    }
                }
            }
        })
        Thread(task, name).apply { isDaemon = true }.start()
        return task
    }
}
