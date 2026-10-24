package com.storage.redirect.x.data.repository

import com.storage.redirect.x.data.service.CommandResult
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PathBrowserRepositoryTest {
    @Test
    fun privateBrowserReadsTheConfiguredUsersBackendAndKeepsRelativePaths() = runBlocking {
        val storage = Files.createTempDirectory("srx-browser-").toFile()
        try {
            val isolated = storage.resolve("Android/data/example.app/sdcard")
            isolated.resolve("Download/Folder's files").mkdirs()
            isolated.resolve(".cache").mkdirs()
            isolated.resolve("release..cache").mkdirs()
            isolated.resolve("plain.txt").writeText("file")
            val commands = mutableListOf<String>()
            val repository = PathBrowserRepository { command ->
                commands += command
                execute(command.replace("/data/media/10", storage.absolutePath))
            }
            assertEquals(listOf(".cache", "Download", "release..cache"),
                repository.listAppPrivateDirectories(10, "example.app", ""))
            assertEquals(listOf("Download/Folder's files"),
                repository.listAppPrivateDirectories(10, "example.app", "Download"))
            assertEquals(emptyList<String>(),
                repository.listAppPrivateDirectories(10, "example.app", "Download/Folder's files"))
            assertTrue(commands.all { it.contains("/data/media/10/Android/data/example.app/sdcard") })
            assertTrue(commands.none { it.contains("/storage/emulated/") })
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun appNotLaunchedYetShowsAnEmptyRootWithoutCreatingIt() = runBlocking {
        val storage = Files.createTempDirectory("srx-unstarted-browser-").toFile()
        try {
            val repository = PathBrowserRepository { command ->
                execute(command.replace("/data/media/0", storage.absolutePath))
            }
            assertEquals(emptyList<String>(), repository.listAppPrivateDirectories(0, "example.new", ""))
            assertFalse(storage.resolve("Android/data/example.new/sdcard").exists())
            assertNull(repository.listAppPrivateDirectories(0, "example.new", "Missing"))
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun privateDirectoryReadErrorsAreNotReportedAsEmptyDirectories() = runBlocking {
        val repository = PathBrowserRepository { CommandResult(1, "", "Permission denied") }
        assertNull(repository.listAppPrivateDirectories(0, "example.app", ""))
    }

    private fun execute(command: String): CommandResult {
        val process = ProcessBuilder("sh", "-c", command).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        return CommandResult(process.waitFor(), output, error)
    }
}
