package com.uclone.restore.sync

import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class InterruptedTransaction(
    val requestId: String,
    val packageName: String,
    val stage: String,
    val rollbackReady: Boolean,
    val targetMutated: Boolean,
    val committed: Boolean,
    val gateState: String,
    val targetUserId: Int,
    val selectedParts: Set<String> = emptySet(),
    val modifiedParts: Set<String> = emptySet(),
)

sealed interface TransactionRecoveryState {
    data object Scanning : TransactionRecoveryState
    data object Ready : TransactionRecoveryState
    data class RootTaskStillRunning(
        val transactions: List<InterruptedTransaction>,
        val liveRequestId: String,
    ) : TransactionRecoveryState
    data class Required(val transactions: List<InterruptedTransaction>) : TransactionRecoveryState
    data class Recovering(
        val transaction: InterruptedTransaction,
        val remaining: List<InterruptedTransaction>,
    ) : TransactionRecoveryState
    data class Failed(val message: String) : TransactionRecoveryState
}

class TransactionRecoveryRepository(
    initialState: TransactionRecoveryState = TransactionRecoveryState.Scanning,
) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<TransactionRecoveryState> = mutableState.asStateFlow()

    fun updateScan(transactions: List<InterruptedTransaction>, liveRequestId: String?) {
        mutableState.value = when {
            liveRequestId != null ->
                TransactionRecoveryState.RootTaskStillRunning(transactions, liveRequestId)
            transactions.isEmpty() -> TransactionRecoveryState.Ready
            else -> TransactionRecoveryState.Required(transactions)
        }
    }

    fun markRecovering(transaction: InterruptedTransaction, remaining: List<InterruptedTransaction>) {
        mutableState.value = TransactionRecoveryState.Recovering(transaction, remaining)
    }

    fun markFailed(message: String) {
        mutableState.value = TransactionRecoveryState.Failed(message)
    }

    suspend fun awaitScanned(): TransactionRecoveryState =
        state.first { it !is TransactionRecoveryState.Scanning }

    fun blockingMessage(type: TaskType): String? {
        val current = state.value
        if (type == TaskType.RECOVER_INTERRUPTED_TRANSACTION) {
            return when (current) {
                is TransactionRecoveryState.Recovering -> null
                TransactionRecoveryState.Scanning -> "正在确认 Root 任务与未完成事务，暂不能启动恢复"
                TransactionRecoveryState.Ready -> "没有已确认需要恢复的事务"
                is TransactionRecoveryState.RootTaskStillRunning -> "上次 Root 数据任务仍在运行，不能重复启动恢复"
                is TransactionRecoveryState.Required -> "事务尚未进入受控恢复状态"
                is TransactionRecoveryState.Failed -> "无法确认 Root 任务状态：${current.message}"
            }
        }
        return when (current) {
            TransactionRecoveryState.Ready -> null
            TransactionRecoveryState.Scanning -> "正在检查上次未完成的数据事务，请稍候"
            is TransactionRecoveryState.RootTaskStillRunning ->
                "上次 Root 数据任务仍在运行，暂不允许开始新的数据任务"
            is TransactionRecoveryState.Required ->
                "检测到 ${current.transactions.size} 个未完成的数据事务，必须先完成安全恢复"
            is TransactionRecoveryState.Recovering ->
                "正在恢复未完成事务 ${current.transaction.requestId}，暂不允许开始新的数据任务"
            is TransactionRecoveryState.Failed ->
                "无法确认上次事务状态：${current.message}"
        }
    }

    companion object {
        fun ready(): TransactionRecoveryRepository =
            TransactionRecoveryRepository(TransactionRecoveryState.Ready)
    }
}

class TransactionRecoveryProbe(private val shell: RootShellExecutor) {
    suspend fun scan(rootDir: String): List<InterruptedTransaction> {
        val result = shell.exec(script(rootDir), timeoutSeconds = 30)
        check(result.isSuccess) {
            result.stderr.lineSequence().firstOrNull(String::isNotBlank)
                ?: "事务扫描失败（exit=${result.exitCode}）"
        }
        return parse(result.stdout)
    }

    companion object {
        internal fun script(rootDir: String): String = """
            ${WorkspacePathGuard.inspect(rootDir)}
            [ "${'$'}UCLONE_WORKSPACE_MISSING" = "0" ] || exit 0
            TRANSACTION_ROOT="${'$'}ROOT/transactions"
            [ -d "${'$'}TRANSACTION_ROOT" ] || exit 0
            transaction_string() {
              sed -n "s/.*\"${'$'}2\":\"\([^\"]*\)\".*/\1/p" "${'$'}1" | head -1
            }
            transaction_scalar() {
              sed -n "s/.*\"${'$'}2\":\([^,}]*\).*/\1/p" "${'$'}1" | head -1
            }
            for TRANSACTION_FILE in "${'$'}TRANSACTION_ROOT"/*/transaction.json; do
              [ -f "${'$'}TRANSACTION_FILE" ] || continue
              TRANSACTION_DIR_NAME=${'$'}(basename "${'$'}(dirname "${'$'}TRANSACTION_FILE")" | tr -cd 'A-Za-z0-9_.-')
              [ -n "${'$'}TRANSACTION_DIR_NAME" ] || TRANSACTION_DIR_NAME=unknown
              REQUEST_ID=${'$'}(transaction_string "${'$'}TRANSACTION_FILE" requestId)
              PACKAGE_NAME=${'$'}(transaction_string "${'$'}TRANSACTION_FILE" packageName)
              STAGE=${'$'}(transaction_string "${'$'}TRANSACTION_FILE" stage)
              ROLLBACK_READY=${'$'}(transaction_scalar "${'$'}TRANSACTION_FILE" rollbackReady)
              TARGET_MUTATED=${'$'}(transaction_scalar "${'$'}TRANSACTION_FILE" targetMutated)
              COMMITTED=${'$'}(transaction_scalar "${'$'}TRANSACTION_FILE" committed)
              GATE_STATE=${'$'}(transaction_string "${'$'}TRANSACTION_FILE" gateState)
              TARGET_USER=${'$'}(transaction_scalar "${'$'}TRANSACTION_FILE" targetUserId)
              SELECTED_PARTS=${'$'}(transaction_string "${'$'}TRANSACTION_FILE" selectedParts)
              MODIFIED_PARTS=${'$'}(transaction_string "${'$'}TRANSACTION_FILE" modifiedParts)
              case "${'$'}REQUEST_ID" in ''|*[!A-Za-z0-9_.-]*) printf 'TXN_INVALID|%s|INVALID_REQUEST_ID\n' "${'$'}TRANSACTION_DIR_NAME"; continue ;; esac
              [ "${'$'}REQUEST_ID" = "${'$'}TRANSACTION_DIR_NAME" ] || {
                printf 'TXN_INVALID|%s|DIRECTORY_ID_MISMATCH\n' "${'$'}TRANSACTION_DIR_NAME"
                continue
              }
              case "${'$'}PACKAGE_NAME" in ''|*[!A-Za-z0-9_.]*) printf 'TXN_INVALID|%s|INVALID_PACKAGE\n' "${'$'}TRANSACTION_DIR_NAME"; continue ;; esac
              case "${'$'}STAGE:${'$'}GATE_STATE" in *[!A-Z0-9_:]*) printf 'TXN_INVALID|%s|INVALID_STATE\n' "${'$'}TRANSACTION_DIR_NAME"; continue ;; esac
              case "${'$'}ROLLBACK_READY:${'$'}TARGET_MUTATED:${'$'}COMMITTED" in
                true:true:true|true:true:false|true:false:true|true:false:false|false:true:true|false:true:false|false:false:true|false:false:false) ;;
                *) printf 'TXN_INVALID|%s|INVALID_BOOLEAN\n' "${'$'}TRANSACTION_DIR_NAME"; continue ;;
              esac
              case "${'$'}TARGET_USER" in ''|*[!0-9]*) printf 'TXN_INVALID|%s|INVALID_TARGET_USER\n' "${'$'}TRANSACTION_DIR_NAME"; continue ;; esac
              case "${'$'}SELECTED_PARTS:${'$'}MODIFIED_PARTS" in *[!a-z_,:]*) printf 'TXN_INVALID|%s|INVALID_PARTS\n' "${'$'}TRANSACTION_DIR_NAME"; continue ;; esac
              case "${'$'}STAGE:${'$'}GATE_STATE" in
                CLEANED:RELEASED|CLEANED:NONE|COMMITTED:RELEASED|COMMITTED:NONE|ROLLED_BACK_COMPLETE:RELEASED|ROLLED_BACK_COMPLETE:NONE|ABORTED:RELEASED|ABORTED:NONE)
                  continue
                  ;;
              esac
              printf 'TXN|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
                "${'$'}REQUEST_ID" "${'$'}PACKAGE_NAME" "${'$'}STAGE" "${'$'}ROLLBACK_READY" \
                "${'$'}TARGET_MUTATED" "${'$'}COMMITTED" "${'$'}GATE_STATE" "${'$'}TARGET_USER" \
                "${'$'}SELECTED_PARTS" "${'$'}MODIFIED_PARTS"
            done
        """.trimIndent()

        internal fun parse(output: String): List<InterruptedTransaction> {
            val transactions = mutableListOf<InterruptedTransaction>()
            output.lineSequence().forEach { line ->
                if (line.startsWith("TXN_INVALID|")) {
                    val fields = line.split('|', limit = 3)
                    val id = fields.getOrNull(1).orEmpty().ifBlank { "unknown" }
                    val reason = fields.getOrNull(2).orEmpty().ifBlank { "MALFORMED" }
                    throw IllegalStateException("发现损坏的事务记录：$id ($reason)")
                }
                if (!line.startsWith("TXN|")) return@forEach
                val fields = line.split('|')
                require(fields.size in setOf(9, 11) && fields[0] == "TXN") { "事务扫描输出格式无效" }
                transactions += InterruptedTransaction(
                    requestId = requireNotNull(fields[1].takeIf(SAFE_ID::matches)) { "事务 requestId 无效" },
                    packageName = requireNotNull(fields[2].takeIf(ANDROID_PACKAGE::matches)) { "事务包名无效" },
                    stage = requireNotNull(fields[3].takeIf(SAFE_STAGE::matches)) { "事务阶段无效" },
                    rollbackReady = requireNotNull(fields[4].toStrictBooleanOrNull()) { "事务回滚状态无效" },
                    targetMutated = requireNotNull(fields[5].toStrictBooleanOrNull()) { "事务修改状态无效" },
                    committed = requireNotNull(fields[6].toStrictBooleanOrNull()) { "事务提交状态无效" },
                    gateState = requireNotNull(fields[7].takeIf(SAFE_STAGE::matches)) { "事务门禁状态无效" },
                    targetUserId = requireNotNull(fields[8].toIntOrNull()?.takeIf { it >= 0 }) { "事务目标用户无效" },
                    selectedParts = fields.getOrNull(9).toSafeParts(),
                    modifiedParts = fields.getOrNull(10).toSafeParts(),
                )
            }
            val duplicate = transactions.groupingBy(InterruptedTransaction::requestId).eachCount()
                .entries.firstOrNull { it.value > 1 }
            require(duplicate == null) { "发现重复事务记录：${duplicate?.key}" }
            return transactions
        }

        private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,128}")
        private val SAFE_STAGE = Regex("[A-Z0-9_]{1,64}")
        private val ANDROID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val SAFE_PARTS = setOf("ce", "de", "external", "media", "obb", "permissions")

        private fun String.toStrictBooleanOrNull(): Boolean? = when (this) {
            "true" -> true
            "false" -> false
            else -> null
        }

        private fun String?.toSafeParts(): Set<String> {
            if (this.isNullOrBlank()) return emptySet()
            return split(',').mapTo(linkedSetOf()) { part ->
                require(part in SAFE_PARTS) { "事务数据部件无效：$part" }
                part
            }
        }
    }
}
