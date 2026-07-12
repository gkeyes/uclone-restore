package com.uclone.restore.ui

import com.uclone.restore.data.SettingsValidation
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.WorkspaceOwnershipReport

internal object WorkspaceOwnershipReportPolicy {
    fun bind(
        report: WorkspaceOwnershipReport,
        settings: UCloneSettings,
        scannedAt: Long = System.currentTimeMillis(),
    ): WorkspaceOwnershipReport {
        val normalized = SettingsValidation.requireValid(settings)
        return report.copy(
            scannedRootDir = normalized.rootDir,
            scannedMainUserId = normalized.mainUserId,
            scannedCloneUserId = normalized.cloneUserId,
            scannedAt = scannedAt,
        )
    }

    fun isFromSettings(report: WorkspaceOwnershipReport, settings: UCloneSettings): Boolean {
        val normalized = runCatching { SettingsValidation.requireValid(settings) }.getOrNull() ?: return false
        return report.scannedRootDir == normalized.rootDir &&
            report.scannedMainUserId == normalized.mainUserId &&
            report.scannedCloneUserId == normalized.cloneUserId
    }

    fun canRepair(report: WorkspaceOwnershipReport, settings: UCloneSettings): Boolean {
        val normalized = runCatching { SettingsValidation.requireValid(settings) }.getOrNull() ?: return false
        return report.nonRootEntries > 0L &&
            isFromSettings(report, normalized) &&
            report.canonicalRoot == normalized.rootDir
    }

    fun retainAfterSettingsChange(
        report: WorkspaceOwnershipReport?,
        previous: UCloneSettings,
        current: UCloneSettings,
    ): WorkspaceOwnershipReport? {
        if (report == null) return null
        val previousNormalized = SettingsValidation.sanitizedForLoad(previous)
        val currentNormalized = runCatching { SettingsValidation.requireValid(current) }.getOrNull() ?: return null
        return report.takeIf {
            previousNormalized.rootDir == currentNormalized.rootDir &&
                previousNormalized.mainUserId == currentNormalized.mainUserId &&
                previousNormalized.cloneUserId == currentNormalized.cloneUserId
        }
    }
}
