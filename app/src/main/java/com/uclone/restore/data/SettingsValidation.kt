package com.uclone.restore.data

import com.uclone.restore.model.UCloneSettings

object SettingsValidation {
    private const val DEFAULT_ROOT_DIR = "/data/adb/uclone"

    fun normalized(settings: UCloneSettings): UCloneSettings = settings.copy(
        rootDir = normalizeRootDir(settings.rootDir),
        cloneUnlockCredential = settings.cloneUnlockCredential.trim(),
    )

    fun error(settings: UCloneSettings): String? {
        val normalized = normalized(settings)
        return when {
            normalized.mainUserId < 0 -> "主系统用户 ID 不能为负数"
            normalized.cloneUserId < 0 -> "分身系统用户 ID 不能为负数"
            normalized.mainUserId == normalized.cloneUserId -> "主系统和分身系统不能使用同一个用户 ID"
            normalized.rootDir.isEmpty() -> "工作目录不能为空"
            !normalized.rootDir.startsWith('/') -> "工作目录必须是绝对路径"
            normalized.rootDir in FORBIDDEN_ROOT_DIRS -> "工作目录范围过大，禁止使用 ${normalized.rootDir}"
            normalized.rootDir.any(Char::isISOControl) -> "工作目录不能包含控制字符"
            normalized.rootDir.any(Char::isWhitespace) -> "工作目录不能包含空白字符"
            normalized.rootDir.contains('\\') -> "工作目录不能包含反斜杠"
            normalized.rootDir.split('/').any { it == "." || it == ".." } -> "工作目录不能包含 . 或 .. 路径段"
            else -> null
        }
    }

    fun requireValid(settings: UCloneSettings): UCloneSettings = normalized(settings).also { normalized ->
        error(normalized)?.let { throw IllegalArgumentException(it) }
    }

    fun sanitizedForLoad(settings: UCloneSettings): UCloneSettings {
        val mainUserId = settings.mainUserId.takeIf { it >= 0 } ?: 0
        var cloneUserId = settings.cloneUserId.takeIf { it >= 0 } ?: 10
        if (cloneUserId == mainUserId) cloneUserId = if (mainUserId == 10) 0 else 10

        val normalizedRoot = normalizeRootDir(settings.rootDir)
        val safeRoot = normalizedRoot.takeIf { rootDir ->
            error(
                UCloneSettings(
                    mainUserId = mainUserId,
                    cloneUserId = cloneUserId,
                    rootDir = rootDir,
                ),
            ) == null
        } ?: DEFAULT_ROOT_DIR

        return settings.copy(
            mainUserId = mainUserId,
            cloneUserId = cloneUserId,
            rootDir = safeRoot,
            cloneUnlockCredential = settings.cloneUnlockCredential.trim(),
        )
    }

    private fun normalizeRootDir(rootDir: String): String {
        val trimmed = rootDir.trim()
        if (trimmed == "/") return trimmed
        return trimmed.trimEnd('/')
    }

    private val FORBIDDEN_ROOT_DIRS = setOf("/", "/data", "/data/adb")
}
