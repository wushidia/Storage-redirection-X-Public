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

    @Test
    fun createFolderUsesTheCurrentUserAndDoesNotReplaceExistingFiles() = runBlocking {
        val storage = Files.createTempDirectory("srx-create-folder-").toFile()
        try {
            storage.resolve("Download").mkdir()
            storage.resolve("Download/existing").writeText("keep")
            val repository = PathBrowserRepository { command ->
                execute(command.replace("/storage/emulated/10", storage.absolutePath))
            }
            val name = "Folder's files; touch injected"
            assertEquals(DirectoryCreateResult.Created, repository.createDirectory(10, null, "Download", name))
            assertTrue(storage.resolve("Download/$name").isDirectory)
            assertFalse(storage.resolve("injected").exists())
            assertEquals(DirectoryCreateResult.AlreadyExists, repository.createDirectory(10, null, "Download", name))
            assertEquals(DirectoryCreateResult.AlreadyExists, repository.createDirectory(10, null, "Download", "existing"))
            assertEquals("keep", storage.resolve("Download/existing").readText())
            listOf("", ".", "..", "../escape", "/outside", "*", "?", "!excluded", "line\nbreak").forEach {
                assertEquals(DirectoryCreateResult.InvalidName, repository.createDirectory(10, null, "Download", it))
            }
            assertEquals(DirectoryCreateResult.Failed, repository.createDirectory(10, null, "Missing", "child"))
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun privateFolderIsCreatedInTheBackendWithParentOwnership() = runBlocking {
        val storage = Files.createTempDirectory("srx-create-private-").toFile()
        try {
            val parent = storage.resolve("Android/data/example.app/sdcard/Download").apply { mkdirs() }
            val repository = PathBrowserRepository { command ->
                execute(command.replace("/data/media/10", storage.absolutePath))
            }
            assertEquals(DirectoryCreateResult.Created, repository.createDirectory(10, "example.app", "Download", ".new"))
            val folder = parent.resolve(".new")
            assertTrue(folder.isDirectory)
            assertEquals(Files.getOwner(parent.toPath()), Files.getOwner(folder.toPath()))
            assertEquals(Files.getAttribute(parent.toPath(), "unix:gid"), Files.getAttribute(folder.toPath(), "unix:gid"))
            assertEquals(0b111111000, (Files.getAttribute(folder.toPath(), "unix:mode") as Int) and 0b111111111)
            assertEquals(listOf("Download/.new"), repository.listAppPrivateDirectories(10, "example.app", "Download"))
        } finally {
            storage.deleteRecursively()
        }
    }

    private fun execute(command: String): CommandResult {
        val process = ProcessBuilder("sh", "-c", command).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        return CommandResult(process.waitFor(), output, error)
    }
}
