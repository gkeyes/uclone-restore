package com.uclone.restore

import android.app.Application
import com.uclone.restore.data.SettingsStore
import com.uclone.restore.launcher.LauncherShortcutController
import com.uclone.restore.root.ProcessRootShellExecutor
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.sync.PackageInspector
import com.uclone.restore.sync.SyncEngine
import com.uclone.restore.sync.TaskLogStore

class UCloneApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val shell = ProcessRootShellExecutor()
        val logStore = TaskLogStore(shell)
        container = AppContainer(
            settingsStore = SettingsStore(this),
            packageInspector = PackageInspector(this, shell),
            launcherShortcutController = LauncherShortcutController(this),
            syncEngine = SyncEngine(
                shell = shell,
                environmentChecker = RootEnvironmentChecker(shell),
                logStore = logStore,
                appPackage = packageName,
            ),
        )
    }
}

data class AppContainer(
    val settingsStore: SettingsStore,
    val packageInspector: PackageInspector,
    val launcherShortcutController: LauncherShortcutController,
    val syncEngine: SyncEngine,
)
