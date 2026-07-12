package com.uclone.restore

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import com.uclone.restore.data.SettingsStore
import com.uclone.restore.external.ExternalActionContract
import com.uclone.restore.external.ExternalActionNotifier
import com.uclone.restore.external.ExternalActionService
import com.uclone.restore.external.ExternalRequestEvent
import com.uclone.restore.external.ExternalRequestStage
import com.uclone.restore.external.ExternalRequestStore
import com.uclone.restore.external.ExternalRequestRecovery
import com.uclone.restore.external.hasLauncherModuleStatusRecipient
import com.uclone.restore.external.toExternalRequestStage
import com.uclone.restore.external.toExternalStatus
import com.uclone.restore.launcher.LauncherShortcutController
import com.uclone.restore.root.ProcessRootShellExecutor
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.sync.PackageInspector
import com.uclone.restore.sync.ActiveRootTaskProbe
import com.uclone.restore.sync.AndroidPackageSigningIdentityProvider
import com.uclone.restore.sync.SyncEngine
import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskLogStore
import com.uclone.restore.sync.TaskPostmortemReconciler
import com.uclone.restore.sync.TaskRepository
import com.uclone.restore.sync.TransactionRecoveryProbe
import com.uclone.restore.sync.TransactionRecoveryRepository
import com.uclone.restore.sync.TransactionRecoveryState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UCloneApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val recoveryStartedAt = System.currentTimeMillis()
        val shell = ProcessRootShellExecutor()
        val logStore = TaskLogStore(
            shell = shell,
            historyFile = filesDir.resolve("task_history_v2.jsonl"),
            legacyHistoryFile = filesDir.resolve("task_history.tsv"),
        )
        val syncEngine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = logStore,
            appPackage = packageName,
            signingIdentityProvider = AndroidPackageSigningIdentityProvider(this),
        )
        val externalRequestStore = ExternalRequestStore(filesDir.resolve("external_requests_v1.jsonl"))
        val transactionRecovery = TransactionRecoveryRepository()
        val activeRootTaskProbe = ActiveRootTaskProbe(shell)
        val transactionRecoveryProbe = TransactionRecoveryProbe(shell)
        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container = AppContainer(
            settingsStore = SettingsStore(this),
            packageInspector = PackageInspector(this, shell),
            launcherShortcutController = LauncherShortcutController(this),
            syncEngine = syncEngine,
            taskRepository = logStore,
            taskCoordinator = TaskCoordinator(logStore, transactionRecovery),
            externalRequestStore = externalRequestStore,
            transactionRecovery = transactionRecovery,
            activeRootTaskProbe = activeRootTaskProbe,
            transactionRecoveryProbe = transactionRecoveryProbe,
            taskPostmortemReconciler = TaskPostmortemReconciler(shell, logStore),
            taskScope = taskScope,
            internalRequestToken = UUID.randomUUID().toString(),
        )
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val startupSettings = container.settingsStore.load()
            val activeRootTaskResult = runCatching {
                activeRootTaskProbe.probe(startupSettings.rootDir)
            }.onFailure { error ->
                transactionRecovery.markFailed(error.message ?: "Root 任务锁检查失败")
                Log.e("UCloneApplication", "Unable to inspect active Root task", error)
            }
            val activeRootTask = activeRootTaskResult.getOrNull()
            if (activeRootTaskResult.isSuccess) {
                reconcileInterruptedTasks(
                    rootDir = startupSettings.rootDir,
                    excludedRequestId = activeRootTask?.takeIf { it.isLive }?.requestId,
                )
            }
            val interruptedTransactions = runCatching {
                transactionRecoveryProbe.scan(startupSettings.rootDir)
            }.onFailure { error ->
                transactionRecovery.markFailed(error.message ?: "事务扫描失败")
                Log.e("UCloneApplication", "Unable to inspect interrupted transactions", error)
            }.getOrNull()
            if (interruptedTransactions != null && activeRootTaskResult.isSuccess) {
                transactionRecovery.updateScan(
                    transactions = interruptedTransactions,
                    liveRequestId = activeRootTask?.takeIf { it.isLive }
                        ?.let { it.recoveryTransactionId ?: it.requestId },
                )
                val recoveryState = transactionRecovery.state.value
                if (activeRootTask?.isLive == true) {
                    monitorLiveRootTask(activeRootTask.requestId)
                } else if (recoveryState is TransactionRecoveryState.Required) {
                    startRequiredRecovery(recoveryState)
                }
            }
            val recoveredEvents = if (activeRootTaskResult.isSuccess) {
                runCatching {
                    ExternalRequestRecovery.recordProcessDeaths(
                        tasks = logStore.all(),
                        store = externalRequestStore,
                        interruptedAfter = recoveryStartedAt,
                        liveRequestIds = setOfNotNull(activeRootTask?.takeIf { it.isLive }?.requestId),
                    )
                }.onFailure { error ->
                    Log.e("UCloneApplication", "Unable to persist process-death diagnostics", error)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            recoveredEvents.filter { hasLauncherModuleStatusRecipient(it.source) }.forEach { event ->
                val status = if (event.stage == com.uclone.restore.external.ExternalRequestStage.STILL_RUNNING) {
                    ExternalActionContract.STATUS_STILL_RUNNING
                } else {
                    ExternalActionContract.STATUS_FAILED_PROCESS_DIED
                }
                sendBroadcast(
                    Intent(ExternalActionContract.ACTION_STATUS)
                        .setPackage(ExternalActionContract.LAUNCHER_MODULE_PACKAGE)
                        .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
                        .putExtra(ExternalActionContract.EXTRA_OPERATION, event.operation)
                        .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, event.packageName)
                        .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, event.requestId)
                        .putExtra(ExternalActionContract.EXTRA_SOURCE, event.source)
                        .putExtra(ExternalActionContract.EXTRA_STATUS, status)
                        .putExtra(ExternalActionContract.EXTRA_MESSAGE, event.message),
                    ExternalActionContract.PERMISSION_CONTROL,
                )
            }
        }
    }

    private fun monitorLiveRootTask(requestId: String) {
        container.taskScope.launch {
            while (true) {
                delay(ROOT_TASK_MONITOR_INTERVAL_MS)
                val settings = container.settingsStore.load()
                val activeResult = runCatching { container.activeRootTaskProbe.probe(settings.rootDir) }
                if (activeResult.isFailure) {
                    container.transactionRecovery.markFailed(
                        activeResult.exceptionOrNull()?.message ?: "Root 任务状态检查失败",
                    )
                    return@launch
                }
                val active = activeResult.getOrNull()
                if (active?.isLive == true) continue

                container.taskRepository.find(requestId)?.let { interrupted ->
                    runCatching {
                        container.taskPostmortemReconciler.reconcile(settings.rootDir, interrupted)
                    }.onFailure { error ->
                        Log.e("UCloneApplication", "Unable to reconcile completed Root task", error)
                    }.getOrNull()?.let(::publishPostmortemTerminal)
                }

                val pending = runCatching { container.transactionRecoveryProbe.scan(settings.rootDir) }
                    .onFailure { error ->
                        container.transactionRecovery.markFailed(error.message ?: "事务扫描失败")
                    }
                    .getOrNull() ?: return@launch
                container.transactionRecovery.updateScan(pending, liveRequestId = null)
                val required = container.transactionRecovery.state.value as? TransactionRecoveryState.Required
                    ?: return@launch
                startRequiredRecovery(required)
                return@launch
            }
        }
    }

    private suspend fun reconcileInterruptedTasks(rootDir: String, excludedRequestId: String?) {
        container.taskRepository.all()
            .asSequence()
            .filter { it.status == com.uclone.restore.model.TaskStatus.INTERRUPTED }
            .filterNot { it.requestId == excludedRequestId }
            .take(MAX_POSTMORTEM_CANDIDATES)
            .forEach { interrupted ->
                runCatching {
                    container.taskPostmortemReconciler.reconcile(rootDir, interrupted)
                }.onFailure { error ->
                    Log.e("UCloneApplication", "Unable to reconcile interrupted task ${interrupted.requestId}", error)
                }.getOrNull()?.let(::publishPostmortemTerminal)
            }
    }

    private fun publishPostmortemTerminal(record: com.uclone.restore.model.TaskRecord) {
        val store = container.externalRequestStore
        val previousEvent = store.all().firstOrNull { it.requestId == record.requestId }
        val source = previousEvent?.source ?: record.audit.source
        val operation = previousEvent?.operation?.takeIf(String::isNotBlank) ?: record.type.name
        val terminalPublished = if (source in EXTERNAL_REQUEST_SOURCES) {
            store.recordReconciledTerminal(
                ExternalRequestEvent(
                    requestId = record.requestId,
                    operation = operation,
                    packageName = record.packageName,
                    source = source,
                    stage = record.status.toExternalRequestStage(),
                    occurredAt = record.finishedAt ?: System.currentTimeMillis(),
                    message = record.message,
                ),
            )
        } else {
            source == ExternalActionContract.SOURCE_APP
        }
        if (source in NOTIFIABLE_SOURCES && terminalPublished) {
            val status = record.status.toExternalStatus()
            ExternalActionNotifier(this).apply {
                clearRunning()
                notifyResult(record.packageName, operation, status, record.message)
            }
            if (hasLauncherModuleStatusRecipient(source)) {
                sendBroadcast(
                    Intent(ExternalActionContract.ACTION_STATUS)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .setComponent(
                            ComponentName(
                                ExternalActionContract.LAUNCHER_MODULE_PACKAGE,
                                ExternalActionContract.LAUNCHER_MODULE_STATUS_RECEIVER,
                            ),
                        )
                        .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
                        .putExtra(ExternalActionContract.EXTRA_OPERATION, operation)
                        .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, record.packageName)
                        .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, record.requestId)
                        .putExtra(ExternalActionContract.EXTRA_SOURCE, source)
                        .putExtra(ExternalActionContract.EXTRA_STATUS, status)
                        .putExtra(ExternalActionContract.EXTRA_MESSAGE, record.message)
                        .putExtra(ExternalActionContract.EXTRA_TASK_TYPE, record.type.name),
                    ExternalActionContract.PERMISSION_CONTROL,
                )
            }
        }
    }

    private fun startRequiredRecovery(required: TransactionRecoveryState.Required) {
        val transaction = required.transactions.firstOrNull() ?: return
        container.transactionRecovery.markRecovering(transaction, required.transactions.drop(1))
        runCatching { ExternalActionService.startRecovery(this, transaction) }
            .onFailure { error ->
                container.transactionRecovery.markFailed(error.message ?: "无法启动事务恢复服务")
                Log.e("UCloneApplication", "Unable to start transaction recovery", error)
            }
    }

    private companion object {
        const val ROOT_TASK_MONITOR_INTERVAL_MS = 1_000L
        const val MAX_POSTMORTEM_CANDIDATES = 8
        val EXTERNAL_REQUEST_SOURCES = setOf(
            ExternalActionContract.SOURCE_MODULE,
            ExternalActionContract.SOURCE_LAUNCHER_MODULE,
            ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT,
        )
        val NOTIFIABLE_SOURCES = EXTERNAL_REQUEST_SOURCES + ExternalActionContract.SOURCE_APP
    }
}

class AppContainer internal constructor(
    val settingsStore: SettingsStore,
    val packageInspector: PackageInspector,
    val launcherShortcutController: LauncherShortcutController,
    val syncEngine: SyncEngine,
    val taskRepository: TaskRepository,
    val taskCoordinator: TaskCoordinator,
    val externalRequestStore: ExternalRequestStore,
    val transactionRecovery: TransactionRecoveryRepository,
    internal val activeRootTaskProbe: ActiveRootTaskProbe,
    val transactionRecoveryProbe: TransactionRecoveryProbe,
    internal val taskPostmortemReconciler: TaskPostmortemReconciler,
    val taskScope: CoroutineScope,
    val internalRequestToken: String,
)
