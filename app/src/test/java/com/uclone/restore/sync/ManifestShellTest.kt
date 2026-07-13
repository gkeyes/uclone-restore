package com.uclone.restore.sync

import com.uclone.restore.root.shellQuote
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestShellTest {
    @Test
    fun userIdReaderAcceptsQuotedAndNumericManifestValues() {
        val manifest = Files.createTempFile("uclone-manifest-user-", ".json").toFile()
        try {
            listOf(
                "{\"sourceUser\":\"10\",\"targetUser\":\"0\"}" to ("10" to "0"),
                "{\"sourceUser\":10,\"targetUser\":0}" to ("10" to "0"),
                "{ \"sourceUser\" : \"10\" , \"targetUser\" : 0 }" to ("10" to "0"),
            ).forEach { (json, expected) ->
                manifest.writeText(json)

                assertEquals(expected.first, readUserId(manifest.absolutePath, "sourceUser"))
                assertEquals(expected.second, readUserId(manifest.absolutePath, "targetUser"))
            }
        } finally {
            manifest.delete()
        }
    }

    @Test
    fun userIdReaderRejectsPartiallyNumericValues() {
        val manifest = Files.createTempFile("uclone-manifest-invalid-user-", ".json").toFile()
        try {
            manifest.writeText("{\"sourceUser\":10evil,\"targetUser\":\"0x0\"}")

            assertEquals("", readUserId(manifest.absolutePath, "sourceUser"))
            assertEquals("", readUserId(manifest.absolutePath, "targetUser"))
        } finally {
            manifest.delete()
        }
    }

    private fun readUserId(path: String, field: String): String {
        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            manifestUserIdReadCommand(field, shellQuote(path)),
        ).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), error)
        return output
    }
}
