package com.uclone.restore.module.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.uclone.restore.module.relay.ModuleConstants
import com.uclone.restore.module.relay.ModuleSettingsStore

class ModuleSettingsActivity : Activity() {
    private lateinit var hookSwitch: Switch
    private lateinit var launchersInput: EditText
    private lateinit var appListContainer: LinearLayout
    private lateinit var diagnostics: TextView
    private lateinit var hookLog: TextView
    private lateinit var relayLog: TextView
    private val appChecks = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        loadValues()
        refreshDiagnostics()
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(title("UClone Launcher Module"))
        root.addView(subtitle("Hook 桌面长按菜单，只把请求交给 UClone Restore。"))

        hookSwitch = Switch(this).apply { text = "启用 Launcher Hook" }
        root.addView(hookSwitch, matchWrap())

        root.addView(label("允许的 Launcher 包名"))
        launchersInput = EditText(this).apply {
            minLines = 1
            setSingleLine(false)
            hint = ModuleConstants.DEFAULT_ALLOWED_LAUNCHERS
        }
        root.addView(launchersInput, matchWrap())

        root.addView(label("允许显示 UClone 菜单的 App"))
        root.addView(subtitle("这里选择的是目标 App。LSPosed 作用域第一版只需要勾选 com.miui.home。"))
        val appButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val refreshApps = Button(this@ModuleSettingsActivity).apply {
                text = "刷新 App"
                setOnClickListener { populateAppList() }
            }
            val clearApps = Button(this@ModuleSettingsActivity).apply {
                text = "清空"
                setOnClickListener { appChecks.values.forEach { it.isChecked = false } }
            }
            val selectUserApps = Button(this@ModuleSettingsActivity).apply {
                text = "用户 App"
                setOnClickListener {
                    appChecks.forEach { (pkg, check) ->
                        check.isChecked = pkg != packageName && pkg != ModuleConstants.UCLONE_PACKAGE && !isSystemPackage(pkg)
                    }
                }
            }
            addView(refreshApps, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearApps, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(selectUserApps, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(appButtons, matchWrap())
        appListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appListContainer, matchWrap())

        val save = Button(this).apply {
            text = "保存设置"
            setOnClickListener {
                saveValues()
                Toast.makeText(this@ModuleSettingsActivity, "设置已保存", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            }
        }
        root.addView(save, matchWrap())

        val reset = Button(this).apply {
            text = "解除 Hook 自动禁用"
            setOnClickListener {
                ModuleSettingsStore.resetAutoDisable(this@ModuleSettingsActivity)
                Toast.makeText(this@ModuleSettingsActivity, "Hook 熔断计数已清零", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            }
        }
        root.addView(reset, matchWrap())

        root.addView(section("签名/权限检测"))
        diagnostics = TextView(this).apply { textSize = 14f }
        root.addView(diagnostics, matchWrap())

        root.addView(section("最近 Hook 事件"))
        hookLog = TextView(this).apply { textSize = 12f }
        root.addView(hookLog, matchWrap())

        root.addView(section("最近 Relay 请求"))
        relayLog = TextView(this).apply { textSize = 12f }
        root.addView(relayLog, matchWrap())

        val refresh = Button(this).apply {
            text = "刷新"
            setOnClickListener { refreshDiagnostics() }
        }
        root.addView(refresh, matchWrap())

        return ScrollView(this).apply { addView(root) }
    }

    private fun loadValues() {
        hookSwitch.isChecked = ModuleSettingsStore.isHookEnabled(this)
        launchersInput.setText(ModuleSettingsStore.allowedLaunchers(this).joinToString(","))
        populateAppList()
    }

    private fun saveValues() {
        ModuleSettingsStore.setHookEnabled(this, hookSwitch.isChecked)
        ModuleSettingsStore.setAllowedLaunchers(this, launchersInput.text.toString())
        ModuleSettingsStore.setAllowedPackageText(
            this,
            appChecks.filterValues { it.isChecked }.keys.sorted().joinToString("\n"),
        )
    }

    private fun populateAppList() {
        appChecks.clear()
        appListContainer.removeAllViews()
        val selected = ModuleSettingsStore.allowedPackageText(this)
            .split(',', '\n', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val apps = queryLaunchableApps()
        apps.forEach { app ->
            val check = CheckBox(this).apply {
                text = "${app.label}\n${app.packageName}"
                isChecked = app.packageName in selected
                textSize = 14f
                setPadding(0, dp(4), 0, dp(4))
            }
            appChecks[app.packageName] = check
            appListContainer.addView(check, matchWrap())
        }
        if (apps.isEmpty()) {
            appListContainer.addView(TextView(this).apply { text = "未读取到可启动 App" }, matchWrap())
        }
    }

    private fun queryLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.toLaunchableApp() }
            .filter { it.packageName != packageName && it.packageName != ModuleConstants.UCLONE_PACKAGE }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<LaunchableApp> { it.isSystem }.thenBy { it.label.lowercase() }.thenBy { it.packageName })
            .toList()
    }

    private fun ResolveInfo.toLaunchableApp(): LaunchableApp? {
        val activity = activityInfo ?: return null
        val pkg = activity.packageName ?: return null
        val label = loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
        return LaunchableApp(pkg, label, isSystemPackage(pkg))
    }

    private fun refreshDiagnostics() {
        diagnostics.text = buildString {
            appendLine("UClone 安装: ${yesNo(isPackageInstalled(ModuleConstants.UCLONE_PACKAGE))}")
            appendLine("UClone 版本: ${packageVersion(ModuleConstants.UCLONE_PACKAGE)}")
            appendLine("模块与 UClone 同签名: ${yesNo(sameSignature())}")
            appendLine("CONTROL 权限授予: ${yesNo(hasControlPermission())}")
            appendLine("ExternalActionService 可解析: ${yesNo(hasUCloneService())}")
            appendLine("ModuleRelayProvider 可用: ${yesNo(hasRelayProvider())}")
            appendLine("Hook 自动禁用: ${yesNo(ModuleSettingsStore.isAutoDisabled(this@ModuleSettingsActivity))}")
            appendLine("连续 Hook 异常: ${ModuleSettingsStore.consecutiveHookErrors(this@ModuleSettingsActivity)}")
        }
        hookLog.text = ModuleSettingsStore.hookEvents(this).ifBlank { "暂无" }
        relayLog.text = ModuleSettingsStore.relayEvents(this).ifBlank { "暂无" }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun packageVersion(packageName: String): String =
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName ?: "unknown"} (${info.longVersionCode})"
        }.getOrDefault("未安装")

    private fun sameSignature(): Boolean =
        packageManager.checkSignatures(packageName, ModuleConstants.UCLONE_PACKAGE) == PackageManager.SIGNATURE_MATCH

    private fun hasControlPermission(): Boolean =
        packageManager.checkPermission(ModuleConstants.CONTROL_PERMISSION, packageName) == PackageManager.PERMISSION_GRANTED

    private fun hasUCloneService(): Boolean =
        runCatching {
            packageManager.getServiceInfo(ComponentName(ModuleConstants.UCLONE_PACKAGE, ModuleConstants.UCLONE_SERVICE), 0)
        }.isSuccess

    private fun hasRelayProvider(): Boolean =
        packageManager.resolveContentProvider(ModuleConstants.PROVIDER_AUTHORITY, 0) != null

    private fun isSystemPackage(packageName: String): Boolean =
        runCatching {
            val flags = packageManager.getApplicationInfo(packageName, 0).flags
            flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        gravity = Gravity.START
    }

    private fun subtitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(4), 0, dp(16))
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
    )
}
