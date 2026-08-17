package com.uclone.crossuser.runtime

import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig

internal object CrossUserSessionValidator {
    fun requireValid(config: CrossUserSessionConfig) {
        require(config.schemaVersion == CrossUserProtocol.SCHEMA_VERSION) { "unsupported schema" }
        require(config.mainUserId == 0) { "main user must be user0 in preview" }
        require(config.targetUserId == 10) { "target user must be user10 in preview" }
        require(config.packageName == CrossUserProtocol.PROBE_PACKAGE) { "probe package only" }
        require(config.componentName == CrossUserProtocol.PROBE_COMPONENT) { "probe component only" }
        require(config.widthPx in 320..4096) { "invalid width" }
        require(config.heightPx in 320..8192) { "invalid height" }
        require(config.densityDpi in 120..1000) { "invalid density" }
        require(config.rotation in 0..3) { "invalid rotation" }
    }
}
