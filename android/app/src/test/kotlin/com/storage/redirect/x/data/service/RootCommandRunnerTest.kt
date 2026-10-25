package com.storage.redirect.x.data.service

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RootCommandRunnerTest {
    private fun runner() = RootCommandRunner { ProcessBuilder("sh", "-c", it).start() }

    @Test
    fun largeErrorOutputCannotBlockStandardOutput() = runBlocking {
        val result = runner().run("index=0; while [ \"\$index\" -lt 4096 ]; do " +
            "printf '%080d\\n' 0 >&2; index=\$((index+1)); done; printf 'done\\n'", 10_000)
        assertEquals(0, result.exitCode)
        assertEquals("done\n", result.stdout)
        assertEquals(81 * 4096, result.stderr.length)
    }

    @Test
    fun nonzeroExitRetainsBothOutputs() = runBlocking {
        val result = runner().run("printf 'out'; printf 'error' >&2; exit 7")
        assertEquals(7, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("error", result.stderr)
    }

    @Test
    fun standardOutputCanBeConsumedWhileTheCommandRuns() = runBlocking {
        val lines = mutableListOf<String>()
        val result = runner().run("printf 'first\\nsecond\\n'", onStdoutLine = { lines += it })
        assertTrue(result.isSuccess)
        assertEquals(listOf("first", "second"), lines)
        assertEquals("first\nsecond\n", result.stdout)
    }

    @Test
    fun timeoutTerminatesTheStartedProcess() = runBlocking {
        lateinit var process: Process
        val runner = RootCommandRunner { ProcessBuilder("sh", "-c", it).start().also { process = it } }
        assertEquals(124, runner.run("exec sleep 10", 100).exitCode)
        assertTrue(process.waitFor(1, TimeUnit.SECONDS))
    }

    @Test
    fun cancellationIsNotReturnedAsCommandFailure() = runBlocking {
        val started = CompletableDeferred<Process>()
        val runner = RootCommandRunner {
            ProcessBuilder("sh", "-c", it).start().also { started.complete(it) }
        }
        val job = async { runner.run("exec sleep 10") }
        val process = started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(process.waitFor(1, TimeUnit.SECONDS))
    }
}
