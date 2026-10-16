package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.SyncOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CriticalBugFixesTest {

    @Test
    fun shellQuotePreventsCommandSubstitutionAndEscapesSingleQuotes() {
        val dangerous = "/app/\$(reboot)/`id`/a'b\"c; rm -rf /"
        val quoted = RemotePathUtils.shellQuote(dangerous)
        assertEquals("'/app/\$(reboot)/`id`/a'\\''b\"c; rm -rf /'", quoted)
    }

    @Test
    fun isSafeToDeleteDirectoryRejectsRootAndDotPaths() {
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory(""))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory("   "))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory("/"))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory("///"))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory("."))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory(".."))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory("/a/.."))
        assertFalse(RemotePathUtils.isSafeToDeleteDirectory("/a/b/../.."))
        assertTrue(RemotePathUtils.isSafeToDeleteDirectory("/app/deploy/dir"))
        assertTrue(RemotePathUtils.isSafeToDeleteDirectory("/var/www/html/"))
    }

    @Test
    fun resolveEditorTempFileIsolatesSameFileNameUnderDifferentRemoteDirs() {
        val root = Files.createTempDirectory("dx-editor-test").toFile()
        try {
            val fileA = RemoteFileBrowserService.resolveEditorTempFile(
                root, "srv-1", "/app/service-a/application.yml", "application.yml"
            )
            val fileB = RemoteFileBrowserService.resolveEditorTempFile(
                root, "srv-1", "/app/service-b/application.yml", "application.yml"
            )
            val fileSameA = RemoteFileBrowserService.resolveEditorTempFile(
                root, "srv-1", "/app/service-a/application.yml", "application.yml"
            )
            val fileOtherServer = RemoteFileBrowserService.resolveEditorTempFile(
                root, "srv-2", "/app/service-a/application.yml", "application.yml"
            )

            assertEquals("application.yml", fileA.name)
            assertEquals("application.yml", fileB.name)
            assertNotEquals(fileA.absolutePath, fileB.absolutePath)
            assertEquals(fileA.absolutePath, fileSameA.absolutePath)
            assertNotEquals(fileA.absolutePath, fileOtherServer.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localTransferCopyPathHandlesSingleFileWithoutIsDirectoryException() {
        val root = Files.createTempDirectory("dx-local-single").toFile()
        try {
            val srcDir = File(root, "src").apply { mkdirs() }
            val singleFile = File(srcDir, "app.jar").apply { writeText("jar-binary-content") }
            val targetDir = File(root, "target/deploy").apply { mkdirs() }

            val service = LocalTransferService()
            val result = service.copyPath(
                sourcePath = singleFile.absolutePath,
                targetDir = targetDir.absolutePath,
                options = SyncOptions()
            )

            assertTrue(result.success, "Expected single file local copy to succeed, got error: ${result.error}")
            assertEquals(1, result.transferredFiles)
            val copiedFile = File(targetDir, "app.jar")
            assertTrue(copiedFile.isFile, "Expected target/deploy/app.jar to be a regular file")
            assertEquals("jar-binary-content", copiedFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localTransferCopyPathHonorsContentsOnlyOptionForDirectories() {
        val root = Files.createTempDirectory("dx-local-dir").toFile()
        try {
            val srcLib = File(root, "project/lib").apply { mkdirs() }
            File(srcLib, "core.jar").writeText("core")
            File(srcLib, "sub").mkdirs()
            File(srcLib, "sub/util.jar").writeText("util")

            val service = LocalTransferService()

            // 1. contentsOnly = true (e.g. /project/lib -> /target/lib): should NOT nest /target/lib/lib
            val targetContentsOnly = File(root, "target-contents/lib").apply { mkdirs() }
            val res1 = service.copyPath(
                sourcePath = srcLib.absolutePath,
                targetDir = targetContentsOnly.absolutePath,
                options = SyncOptions(contentsOnly = true)
            )
            assertTrue(res1.success)
            assertTrue(File(targetContentsOnly, "core.jar").isFile)
            assertTrue(File(targetContentsOnly, "sub/util.jar").isFile)
            assertFalse(File(targetContentsOnly, "lib").exists())

            // 2. contentsOnly = false (e.g. /project/lib -> /target/parent): should copy directory itself to /target/parent/lib
            val targetParent = File(root, "target-parent").apply { mkdirs() }
            val res2 = service.copyPath(
                sourcePath = srcLib.absolutePath,
                targetDir = targetParent.absolutePath,
                options = SyncOptions(contentsOnly = false)
            )
            assertTrue(res2.success)
            assertTrue(File(targetParent, "lib/core.jar").isFile)
            assertTrue(File(targetParent, "lib/sub/util.jar").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
