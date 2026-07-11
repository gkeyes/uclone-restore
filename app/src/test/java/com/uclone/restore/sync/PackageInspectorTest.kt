package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class PackageInspectorTest {
    @Test
    fun packageListParserKeepsUserSpecificUidAndPackagesWithoutUid() {
        assertEquals(
            linkedMapOf(
                "com.example.main" to 10332,
                "com.example.clone" to 1010332,
                "com.example.unknown" to null,
            ),
            parsePackageUidList(
                """
                    package:com.example.main uid:10332
                    ignored line
                    package:com.example.clone uid:1010332
                    package:com.example.unknown
                """.trimIndent(),
            ),
        )
    }
}
