package com.uclone.restore.sync

data class RestoreCompatibilityOptions(
    val allowVersionMismatch: Boolean = false,
    val allowLegacyIdentity: Boolean = false,
)
