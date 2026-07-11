package com.uclone.restore

import android.app.Application
import android.content.Intent
import android.util.Log
import com.uclone.restore.data.SettingsStore
import com.uclone.restore.external.ExternalActionContract
import com.uclone.restore.external.ExternalRequestStore
import com.uclone.restore.external.ExternalRequestRecovery
import com.uclone.restore.external.hasLauncherModuleStatusRecipient
import com.uclone.restore.launcher.LauncherShortcutController
import com.uclone.restore.root.ProcessRootShellExecutor
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.sync.PackageInspector
import com.uclone.restore.sync.ActiveRootTaskProbe
import com.uclone.restore.sync.SyncEngine
import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskLogStore
import com.uclone.restore.sync.TaskRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        )
        val externalRequestStore = ExternalRequestStore(filesDir.resolve("external_requests_v1.jsonl"))
        container = AppContainer(
            settingsStore = SettingsStore(this),
            packageInspector = PackageInspector(this, shell),
            launcherShortcutController = LauncherShortcutController(this),
            syncEngine = syncEngine,
            taskRepository = logStore,
            taskCoordinator = TaskCoordinator(logStore),
            externalRequestStore = externalRequestStore,
            internalRequestToken = UUID.randomUUID().toString(),
        )
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val activeRootTask = runCatching {
                ActiveRootTaskProbe(shell).probe(container.settingsStore.load().rootDir)
            }.onFailure { error ->
                Log.e("UCloneApplication", "Unable to inspect active Root task", error)
            }.getOrNull()
            val recoveredEvents = runCatching {
                ExternalRequestRecovery.recordProcessDeaths(
                    tasks = logStore.all(),
                    store = externalRequestStore,
                    interruptedAfter = recoveryStartedAt,
                    liveRequestIds = setOfNotNull(activeRootTask?.takeIf { it.isLive }?.requestId),
                )
            }.onFailure { error ->
                Log.e("UCloneApplication", "Unable to persist process-death diagnostics", error)
            }.getOrDefault(emptyList())
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
}

data class AppContainer(
    val settingsStore: SettingsStore,
    val packageInspector: PackageInspector,
    val launcherShortcutController: LauncherShortcutController,
    val syncEngine: SyncEngine,
    val taskRepository: TaskRepository,
    val taskCoordinator: TaskCoordinator,
    val externalRequestStore: ExternalRequestStore,
    val internalRequestToken: String,
)
