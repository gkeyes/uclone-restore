package com.uclone.restore.sync

internal const val CURRENT_MANIFEST_SCHEMA = 5

internal fun manifestUserIdReadCommand(field: String, pathExpression: String): String {
    require(field == "sourceUser" || field == "targetUser") { "Unsupported manifest user field: $field" }
    require(pathExpression.isNotBlank()) { "Manifest path expression is required" }
    return "sed -n -e 's/.*\"$field\"[[:space:]]*:[[:space:]]*\"\\([0-9][0-9]*\\)\"[[:space:]]*[,}].*/\\1/p' " +
        "-e 's/.*\"$field\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\)[[:space:]]*[,}].*/\\1/p' $pathExpression 2>/dev/null | head -1"
}
