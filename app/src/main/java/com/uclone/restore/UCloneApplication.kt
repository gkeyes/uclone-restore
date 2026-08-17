package com.uclone.restore

import android.app.Application
import com.uclone.restore.data.SettingsStore
import com.uclone.restore.launcher.LauncherShortcutController
import com.uclone.restore.root.ProcessRootShellExecutor
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.sync.PackageInspector
import com.uclone.restore.sync.SyncEngine
import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskLogStore
import com.uclone.restore.sync.TaskRepository
import com.uclone.crossuser.runtime.CrossUserRuntime
import com.uclone.crossuser.runtime.HostOwnedCrossUserRuntime
import com.uclone.restore.crossuser.session.CrossUserResourceGate
import com.uclone.restore.crossuser.session.CrossUserSessionCoordinator
import java.util.UUID

class UCloneApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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
        val crossUserResourceGate = CrossUserResourceGate()
        val crossUserRuntime = HostOwnedCrossUserRuntime(this)
        container = AppContainer(
            settingsStore = SettingsStore(this),
            packageInspector = PackageInspector(this, shell),
            launcherShortcutController = LauncherShortcutController(this),
            syncEngine = syncEngine,
            taskRepository = logStore,
            taskCoordinator = TaskCoordinator(logStore, crossUserResourceGate),
            crossUserRuntime = crossUserRuntime,
            crossUserSessionCoordinator = CrossUserSessionCoordinator(crossUserRuntime, crossUserResourceGate),
            crossUserResourceGate = crossUserResourceGate,
            internalRequestToken = UUID.randomUUID().toString(),
        )
    }
}

data class AppContainer(
    val settingsStore: SettingsStore,
    val packageInspector: PackageInspector,
    val launcherShortcutController: LauncherShortcutController,
    val syncEngine: SyncEngine,
    val taskRepository: TaskRepository,
    val taskCoordinator: TaskCoordinator,
    val crossUserRuntime: CrossUserRuntime,
    val crossUserSessionCoordinator: CrossUserSessionCoordinator,
    val crossUserResourceGate: CrossUserResourceGate,
    val internalRequestToken: String,
)
