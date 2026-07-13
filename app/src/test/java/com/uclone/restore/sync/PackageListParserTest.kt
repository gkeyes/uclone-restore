package com.uclone.restore.sync

import com.uclone.restore.root.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackageListParserTest {
    @Test
    fun parsesExactPackageAndUidOutput() {
        val packages = PackageListParser.requirePackages(
            userId = 10,
            result = ShellResult(
                exitCode = 0,
                stdout = "package:com.example.one uid:1010123\npackage:com.example.two uid:1010456\n",
                stderr = "",
            ),
        )

        assertEquals(1010123, packages["com.example.one"])
        assertEquals(1010456, packages["com.example.two"])
    }

    @Test
    fun rejectsFailedEmptyOrTruncatedPackageQueries() {
        assertFailsWith<IllegalStateException> {
            PackageListParser.requirePackages(0, ShellResult(2, "", "unknown user"))
        }
        assertFailsWith<IllegalStateException> {
            PackageListParser.requirePackages(0, ShellResult(0, "", ""))
        }
        assertFailsWith<IllegalStateException> {
            PackageListParser.requirePackages(
                0,
                ShellResult(0, "package:com.example uid:10123", "", outputTruncated = true),
            )
        }
    }
}
