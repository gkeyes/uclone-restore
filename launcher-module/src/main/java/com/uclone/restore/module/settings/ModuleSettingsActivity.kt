package com.uclone.restore.module.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.uclone.restore.module.relay.ModuleConstants
import com.uclone.restore.module.relay.ModuleSettingsStore

class ModuleSettingsActivity : Activity() {
    private lateinit var hookSwitch: Switch
    private lateinit var pageContainer: LinearLayout
    private lateinit var headerSubtitle: TextView
    private lateinit var diagnosticsContainer: LinearLayout
    private lateinit var appListContainer: LinearLayout
    private lateinit var selectedCountText: TextView
    private lateinit var hookLog: TextView
    private lateinit var relayLog: TextView

    private val tabButtons = mutableMapOf<SettingsPage, TextView>()
    private val appFilterButtons = mutableMapOf<AppFilter, TextView>()
    private val logFilterButtons = mutableMapOf<LogFilter, TextView>()
    private var selectedPackages = mutableSetOf<String>()
    private var launchableApps = emptyList<LaunchableApp>()
    private var currentPage = SettingsPage.STATUS
    private var currentAppFilter = AppFilter.SELECTED
    private var currentLogFilter = LogFilter.ALL

    private val isDarkMode: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val SURFACE: Int get() = if (isDarkMode) Color.BLACK else Color.rgb(242, 242, 247)
    private val CARD_SURFACE: Int get() = if (isDarkMode) Color.rgb(28, 28, 30) else Color.WHITE
    private val TEXT_PRIMARY: Int get() = if (isDarkMode) Color.WHITE else Color.BLACK
    private val TEXT_SECONDARY: Int get() = if (isDarkMode) Color.rgb(152, 152, 157) else Color.rgb(108, 108, 112)
    private val TEXT_TERTIARY: Int get() = if (isDarkMode) Color.rgb(99, 99, 102) else Color.rgb(142, 142, 147)
    private val BLUE: Int get() = if (isDarkMode) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)
    private val ON_PRIMARY: Int get() = Color.WHITE
    private val GREEN: Int get() = if (isDarkMode) Color.rgb(48, 209, 88) else Color.rgb(52, 199, 89)
    private val ORANGE: Int get() = if (isDarkMode) Color.rgb(255, 159, 10) else Color.rgb(255, 149, 0)
    private val RED: Int get() = if (isDarkMode) Color.rgb(255, 69, 58) else Color.rgb(255, 59, 48)
    private val SEPARATOR: Int get() = if (isDarkMode) Color.rgb(56, 56, 58) else Color.rgb(198, 198, 200)
    private val GLASS_BORDER: Int get() = colorWithAlpha(TEXT_TERTIARY, if (isDarkMode) 96 else 72)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()

        launchableApps = queryLaunchableApps()
        selectedPackages = ModuleSettingsStore.allowedPackageSet(this).toMutableSet()
        sanitizeSelectedPackages()
        setContentView(buildContent())
        showPage(SettingsPage.STATUS)
        refreshDiagnostics()
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = SURFACE
        window.decorView.systemUiVisibility = if (isDarkMode) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun buildContent(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), statusBarHeight() + dp(12), dp(16), navigationBarHeight() + dp(14))
            background = softBackground()

            addView(headerCard(), matchWrap())
            addView(space(10))
            addView(tabBar(), matchWrap())
            addView(space(10))

            pageContainer = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(pageContainer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

    private fun headerCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(2))
            val top = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val mark = TextView(this@ModuleSettingsActivity).apply {
                text = "U"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(ON_PRIMARY)
                background = roundedSolid(BLUE, dp(13))
            }
            top.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))

            val titleGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            titleGroup.addView(text("UClone Module", 24f, TEXT_PRIMARY, true))
            headerSubtitle = text("桌面长按入口控制", 13f, TEXT_SECONDARY, false)
            titleGroup.addView(headerSubtitle)
            top.addView(titleGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            hookSwitch = Switch(this@ModuleSettingsActivity).apply {
                text = ""
                contentDescription = "启用桌面长按入口"
                showText = false
                isChecked = ModuleSettingsStore.isHookEnabled(this@ModuleSettingsActivity)
                minWidth = dp(52)
                minimumWidth = dp(52)
                minHeight = dp(32)
                applySwitchStyle()
                setOnCheckedChangeListener { _, checked ->
                    persistHookEnabled(checked)
                }
            }
            top.addView(hookSwitch, LinearLayout.LayoutParams(dp(58), dp(48)))
            addView(top, matchWrap())
        }

    private fun tabBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassDrawable(dp(24), alpha = 238, strokeColor = GLASS_BORDER)
            SettingsPage.entries.forEach { page ->
                val item = TextView(this@ModuleSettingsActivity).apply {
                    text = page.label
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setOnClickListener { showPage(page) }
                }
                tabButtons[page] = item
                addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }

    private fun showPage(page: SettingsPage) {
        currentPage = page
        updateTabStyles()
        pageContainer.removeAllViews()
        val content = when (page) {
            SettingsPage.STATUS -> statusPage()
            SettingsPage.APPS -> appsPage()
            SettingsPage.CONTROL -> controlPage()
            SettingsPage.LOGS -> logsPage()
        }
        pageContainer.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        refreshDiagnostics()
    }

    private fun statusPage(): ScrollView =
        pageScroll().apply {
            val root = pageColumn()
            root.addView(sectionHeader("状态", "模块、权限和目标 App 的当前状态。"))
            root.addView(space(10))
            root.addView(statusSummaryCard(), matchWrap())
            root.addView(space(10))
            root.addView(diagnosticsCard(), matchWrap())
            root.addView(space(10))
            root.addView(navigationRow(
                title = "目标 App",
                subtitle = "进入 App 页选择显示快捷操作的应用",
                value = "${selectedPackages.size} 个",
                onClick = { showPage(SettingsPage.APPS) },
            ), matchWrap())
            addView(root, matchWrap())
        }

    private fun statusSummaryCard(): LinearLayout =
        glassCard().apply {
            val hookEnabled = ModuleSettingsStore.isHookEnabled(this@ModuleSettingsActivity)
            val grid = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val row1 = LinearLayout(this@ModuleSettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(metricTile(if (hookEnabled) "可用" else "关闭", "Hook", if (hookEnabled) GREEN else ORANGE), weightedWrap())
            row1.addView(space(8, horizontal = true))
            row1.addView(metricTile("${selectedPackages.size} 个", "目标 App", BLUE), weightedWrap())
            grid.addView(row1, matchWrap())
            grid.addView(space(8))
            val row2 = LinearLayout(this@ModuleSettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(metricTile(yesNo(hasControlPermission()), "CONTROL", if (hasControlPermission()) GREEN else ORANGE), weightedWrap())
            row2.addView(space(8, horizontal = true))
            row2.addView(metricTile(ModuleSettingsStore.consecutiveHookErrors(this@ModuleSettingsActivity).toString(), "Hook 异常", ORANGE), weightedWrap())
            grid.addView(row2, matchWrap())
            addView(grid, matchWrap())
        }

    private fun diagnosticsCard(): LinearLayout =
        glassCard().apply {
            val header = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(cardTitle("健康检查"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(iconButton("↻", "刷新") { refreshDiagnostics() })
            addView(header, matchWrap())
            addView(space(8))
            diagnosticsContainer = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(diagnosticsContainer, matchWrap())
        }

    private fun appsPage(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val header = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val title = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("App", 28f, TEXT_PRIMARY, true))
                    addView(bodyText("为桌面长按菜单启用目标应用。"))
                }
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton("↻", "刷新 App") {
                    refreshApps()
                    Toast.makeText(this@ModuleSettingsActivity, "App 列表已刷新", Toast.LENGTH_SHORT).show()
                })
            }
            addView(header, matchWrap())
            addView(space(10))
            addView(appFilterBar(), matchWrap())
            addView(space(10))

            val listSurface = glassCard().apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val scroller = ScrollView(this@ModuleSettingsActivity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            appListContainer = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroller.addView(appListContainer, matchWrap())
            listSurface.addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(listSurface, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(space(8))
            addView(appBottomActionBar(), matchWrap())
            populateAppList()
        }

    private fun appFilterBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassDrawable(dp(22))
            AppFilter.entries.forEach { filter ->
                val button = TextView(this@ModuleSettingsActivity).apply {
                    text = filter.label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        currentAppFilter = filter
                        updateAppFilterStyles()
                        populateAppList()
                    }
                }
                appFilterButtons[filter] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            updateAppFilterStyles()
        }

    private fun controlPage(): ScrollView =
        pageScroll().apply {
            val root = pageColumn()
            root.addView(sectionHeader("控制", "Hook 开关、作用域和恢复控制。"))
            root.addView(space(10))
            root.addView(glassCard().apply {
                val row = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val copy = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(cardTitle("Hook"))
                    addView(bodyText("桌面长按入口"))
                }
                row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(hookSwitchMirror(), LinearLayout.LayoutParams(dp(58), dp(48)))
                addView(row, matchWrap())
                addView(space(10))
                addView(infoStrip("只负责把快捷入口注入桌面。真实备份、恢复和 Root 操作仍由 UClone Restore 执行。"))
            }, matchWrap())
            root.addView(space(10))
            root.addView(glassCard().apply {
                addView(cardTitle("作用域"))
                addView(space(8))
                addView(infoStrip("LSPosed 只勾选 com.miui.home。目标 App 在 App 页选择，不进入作用域。"))
                addView(space(8))
                addView(statusPill("com.miui.home", BLUE))
            }, matchWrap())
            root.addView(space(10))
            root.addView(glassCard().apply {
                addView(cardTitle("恢复"))
                addView(space(8))
                addView(bodyText("Hook 自动禁用后才需要解除。正常情况下不用点。"))
                addView(space(10))
                addView(secondaryButton("解除 Hook 自动禁用", {
                    ModuleSettingsStore.resetAutoDisable(this@ModuleSettingsActivity)
                    Toast.makeText(this@ModuleSettingsActivity, "Hook 熔断计数已清零", Toast.LENGTH_SHORT).show()
                    refreshDiagnostics()
                }, matchWrap()))
            }, matchWrap())
            root.addView(space(14))
            root.addView(primaryButton("保存设置", {
                saveValues()
                Toast.makeText(this@ModuleSettingsActivity, "设置已保存", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            }, matchWrap()))
            addView(root, matchWrap())
        }

    private fun logsPage(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val header = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val title = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("日志", 28f, TEXT_PRIMARY, true))
                    addView(bodyText("Hook、Relay 事件与诊断。"))
                }
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton("↻", "刷新") { refreshDiagnostics() })
                addView(space(8, horizontal = true))
                addView(iconButton("⧉", "复制") { copyLogs() })
            }
            addView(header, matchWrap())
            addView(space(10))
            addView(logFilterBar(), matchWrap())
            addView(space(10))

            val logSurface = glassCard().apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            val scroller = ScrollView(this@ModuleSettingsActivity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val body = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            hookLog = logText()
            relayLog = logText()
            body.addView(hookLog, matchWrap())
            body.addView(space(10))
            body.addView(relayLog, matchWrap())
            scroller.addView(body, matchWrap())
            logSurface.addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(logSurface, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(space(10))
            addView(secondaryButton("清空模块日志", {
                ModuleSettingsStore.clearEvents(this@ModuleSettingsActivity)
                refreshDiagnostics()
                Toast.makeText(this@ModuleSettingsActivity, "模块日志已清空", Toast.LENGTH_SHORT).show()
            }, matchWrap(), danger = true))
        }

    private fun logFilterBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassDrawable(dp(22))
            LogFilter.entries.forEach { filter ->
                val button = TextView(this@ModuleSettingsActivity).apply {
                    text = filter.label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        currentLogFilter = filter
                        updateLogFilterStyles()
                        refreshDiagnostics()
                    }
                }
                logFilterButtons[filter] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            updateLogFilterStyles()
        }

    private fun hookSwitchMirror(): Switch =
        Switch(this).apply {
            text = ""
            contentDescription = "启用桌面长按入口"
            showText = false
            isChecked = hookSwitch.isChecked
            minWidth = dp(52)
            minimumWidth = dp(52)
            minHeight = dp(32)
            applySwitchStyle()
            setOnCheckedChangeListener { _, checked ->
                if (hookSwitch.isChecked != checked) {
                    hookSwitch.isChecked = checked
                } else {
                    persistHookEnabled(checked)
                }
            }
        }

    private fun persistHookEnabled(enabled: Boolean) {
        ModuleSettingsStore.setHookEnabled(this, enabled)
        if (currentPage == SettingsPage.STATUS) {
            showPage(SettingsPage.STATUS)
        } else {
            refreshDiagnostics()
        }
    }

    private fun refreshApps() {
        launchableApps = queryLaunchableApps()
        sanitizeSelectedPackages()
        populateAppList()
    }

    private fun saveValues() {
        sanitizeSelectedPackages()
        ModuleSettingsStore.setHookEnabled(this, hookSwitch.isChecked)
        ModuleSettingsStore.setAllowedPackageText(this, selectedPackages.sorted().joinToString("\n"))
        updateSelectedCount()
        updateHeaderSubtitle()
    }

    private fun sanitizeSelectedPackages() {
        val blockedPackages = setOf(packageName, ModuleConstants.UCLONE_PACKAGE)
        val systemPackages = launchableApps.filter { it.isSystem }.map { it.packageName }.toSet()
        selectedPackages.removeAll(blockedPackages + systemPackages)
    }

    private fun populateAppList() {
        if (!::appListContainer.isInitialized) return
        appListContainer.removeAllViews()
        val apps = filteredApps()
        apps.forEachIndexed { index, app ->
            appListContainer.addView(appRow(app), matchWrap())
            if (index != apps.lastIndex) appListContainer.addView(divider(), matchWrap())
        }
        if (apps.isEmpty()) {
            appListContainer.addView(emptyState("没有符合条件的 App"))
        }
        updateSelectedCount()
    }

    private fun filteredApps(): List<LaunchableApp> =
        when (currentAppFilter) {
            AppFilter.SELECTED -> launchableApps.filter { it.packageName in selectedPackages }
            AppFilter.USER -> launchableApps.filter { !it.isSystem }
            AppFilter.ALL -> launchableApps
        }

    private fun appRow(app: LaunchableApp): LinearLayout {
        val toggle = Switch(this).apply {
            text = ""
            contentDescription = "${app.label} 快捷入口"
            showText = false
            isChecked = app.packageName in selectedPackages
            isEnabled = !app.isSystem
            minWidth = dp(52)
            minimumWidth = dp(52)
            minHeight = dp(32)
            applySwitchStyle()
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedPackages.add(app.packageName)
                } else {
                    selectedPackages.remove(app.packageName)
                }
                updateSelectedCount()
                updateHeaderSubtitle()
                if (currentAppFilter == AppFilter.SELECTED && !checked) {
                    populateAppList()
                }
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            alpha = if (app.isSystem) 0.48f else 1f
            background = clippedRipple(
                content = roundedSolid(Color.TRANSPARENT, dp(14)),
                radius = dp(14),
                rippleColor = colorWithAlpha(BLUE, 18),
            )
            setOnClickListener {
                if (!app.isSystem) toggle.performClick()
            }

            addView(ImageView(this@ModuleSettingsActivity).apply {
                setImageDrawable(app.icon)
                background = roundedSolid(CARD_SURFACE, dp(11))
                clipToOutline = true
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))

            val labelGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                val titleLine = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(app.label, 15f, TEXT_PRIMARY, true).apply {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (app.isSystem) {
                        addView(space(6, horizontal = true))
                        addView(tinyTag("系统", ORANGE))
                    }
                }
                addView(titleLine, matchWrap())
                addView(text(app.packageName, 11f, TEXT_TERTIARY, false).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })
            }
            addView(labelGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(dp(58), dp(48)))
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
        val icon = runCatching { loadIcon(packageManager) }.getOrDefault(packageManager.defaultActivityIcon)
        return LaunchableApp(pkg, label, icon, isSystemPackage(pkg))
    }

    private fun refreshDiagnostics() {
        updateHeaderSubtitle()
        if (::diagnosticsContainer.isInitialized) {
            diagnosticsContainer.removeAllViews()
            val autoDisabled = ModuleSettingsStore.isAutoDisabled(this)
            val rows = listOf(
                Triple("UClone", packageVersion(ModuleConstants.UCLONE_PACKAGE), isPackageInstalled(ModuleConstants.UCLONE_PACKAGE)),
                Triple("同签名", yesNo(sameSignature()), sameSignature()),
                Triple("CONTROL 权限", yesNo(hasControlPermission()), hasControlPermission()),
                Triple("ActionService", yesNo(hasUCloneService()), hasUCloneService()),
                Triple("RelayProvider", yesNo(hasRelayProvider()), hasRelayProvider()),
                Triple("Hook 自动禁用", if (autoDisabled) "已触发" else "未触发", !autoDisabled),
                Triple("连续 Hook 异常", ModuleSettingsStore.consecutiveHookErrors(this).toString(), ModuleSettingsStore.consecutiveHookErrors(this) == 0),
                Triple("目标 App", "${selectedPackages.size} 个", selectedPackages.isNotEmpty()),
            )
            rows.forEachIndexed { index, row ->
                diagnosticsContainer.addView(diagnosticRow(row.first, row.second, row.third), matchWrap())
                if (index != rows.lastIndex) diagnosticsContainer.addView(divider(), matchWrap())
            }
        }
        if (::hookLog.isInitialized && ::relayLog.isInitialized) {
            val hook = ModuleSettingsStore.hookEvents(this).ifBlank { "暂无 Hook 事件" }
            val relay = ModuleSettingsStore.relayEvents(this).ifBlank { "暂无 Relay 事件" }
            hookLog.visibility = if (currentLogFilter == LogFilter.RELAY) View.GONE else View.VISIBLE
            relayLog.visibility = if (currentLogFilter == LogFilter.HOOK) View.GONE else View.VISIBLE
            hookLog.text = "Hook\n$hook"
            relayLog.text = "Relay\n$relay"
        }
        updateSelectedCount()
    }

    private fun appBottomActionBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = glassDrawable(dp(22), alpha = 238)

            selectedCountText = text("", 13f, TEXT_SECONDARY, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(selectedCountText, matchWrap())
            addView(space(8))
            addView(primaryButton("保存目标 App", {
                saveValues()
                Toast.makeText(this@ModuleSettingsActivity, "目标 App 已保存", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            }, matchWrap().apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            }))
        }

    private fun updateHeaderSubtitle() {
        if (::headerSubtitle.isInitialized) {
            val status = if (ModuleSettingsStore.isHookEnabled(this)) "已启用" else "已关闭"
            headerSubtitle.text = "$status · ${selectedPackages.size} 个目标"
        }
    }

    private fun updateSelectedCount() {
        if (::selectedCountText.isInitialized) {
            selectedCountText.text = "已选择 ${selectedPackages.size} 个 App"
        }
    }

    private fun updateTabStyles() {
        tabButtons.forEach { (page, button) ->
            val selected = page == currentPage
            button.isSelected = selected
            button.contentDescription = if (selected) "${page.label}，当前页面" else "打开${page.label}页面"
            button.setTextColor(if (selected) BLUE else TEXT_SECONDARY)
            button.background = segmentedButtonBackground(selected, dp(18))
        }
    }

    private fun updateAppFilterStyles() {
        appFilterButtons.forEach { (filter, button) ->
            val selected = filter == currentAppFilter
            button.isSelected = selected
            button.contentDescription = if (selected) "${filter.label}筛选，已选中" else "使用${filter.label}筛选"
            button.setTextColor(if (selected) BLUE else TEXT_SECONDARY)
            button.background = segmentedButtonBackground(selected, dp(18))
        }
    }

    private fun updateLogFilterStyles() {
        logFilterButtons.forEach { (filter, button) ->
            val selected = filter == currentLogFilter
            button.isSelected = selected
            button.contentDescription = if (selected) "${filter.label}日志，已选中" else "显示${filter.label}日志"
            button.setTextColor(if (selected) BLUE else TEXT_SECONDARY)
            button.background = segmentedButtonBackground(selected, dp(18))
        }
    }

    private fun copyLogs() {
        val text = buildString {
            appendLine("Hook")
            appendLine(ModuleSettingsStore.hookEvents(this@ModuleSettingsActivity).ifBlank { "暂无" })
            appendLine()
            appendLine("Relay")
            appendLine(ModuleSettingsStore.relayEvents(this@ModuleSettingsActivity).ifBlank { "暂无" })
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("UClone Module Logs", text))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
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

    private fun pageScroll(): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

    private fun pageColumn(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 28f, TEXT_PRIMARY, true))
            addView(bodyText(subtitle))
        }

    private fun glassCard(compact: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), if (compact) dp(12) else dp(16), dp(16), if (compact) dp(12) else dp(16))
            background = glassDrawable()
            elevation = 0f
        }

    private fun cardTitle(value: String): TextView =
        text(value, 17f, TEXT_PRIMARY, true)

    private fun bodyText(value: String): TextView =
        text(value, 15f, TEXT_SECONDARY, false).apply {
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun logText(): TextView =
        text("", 12f, TEXT_TERTIARY, false).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }

    private fun infoStrip(value: String): TextView =
        bodyText(value).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedSolid(if (isDarkMode) Color.rgb(44, 44, 46) else Color.rgb(247, 247, 250), dp(12))
        }

    private fun emptyState(value: String): TextView =
        bodyText(value).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
        }

    private fun navigationRow(title: String, subtitle: String, value: String, onClick: () -> Unit): LinearLayout =
        glassCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = clippedRipple(glassDrawable(), dp(12), colorWithAlpha(BLUE, 18))
            setOnClickListener { onClick() }
            val label = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(cardTitle(title))
                addView(bodyText(subtitle))
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, 18f, TEXT_PRIMARY, true))
            addView(space(10, horizontal = true))
            addView(text("›", 30f, BLUE, true))
        }

    private fun metricTile(value: String, label: String, color: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedSolid(colorWithAlpha(color, 24), dp(12))
            addView(text(value, 18f, color, true))
            addView(text(label, 12f, TEXT_SECONDARY, true))
        }

    private fun diagnosticRow(label: String, value: String, ok: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(statusDot(if (ok) GREEN else ORANGE), LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(space(10, horizontal = true))
            addView(text(label, 14f, TEXT_SECONDARY, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, 14f, if (ok) TEXT_PRIMARY else ORANGE, true).apply {
                gravity = Gravity.END
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
        }

    private fun statusPill(label: String, color: Int): TextView =
        TextView(this).apply {
            text = "● $label"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedSolid(colorWithAlpha(color, 28), dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
        }

    private fun tinyTag(label: String, color: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, dp(7), 0)
            background = roundedSolid(colorWithAlpha(color, 26), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22))
        }

    private fun statusDot(color: Int): View =
        View(this).apply {
            background = roundedSolid(color, dp(5))
        }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        primaryButton(label, onClick, matchWrap())

    private fun primaryButton(label: String, onClick: () -> Unit, params: LinearLayout.LayoutParams): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(ON_PRIMARY)
            background = primaryButtonDrawable()
            setOnClickListener { onClick() }
            layoutParams = params.apply { height = dp(50) }
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView =
        secondaryButton(label, onClick, matchWrap(), danger = false)

    private fun secondaryButton(
        label: String,
        onClick: () -> Unit,
        params: LinearLayout.LayoutParams,
        danger: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(if (danger) RED else BLUE)
            background = clippedRipple(
                content = roundedSolid(
                    if (danger) colorWithAlpha(RED, 20) else colorWithAlpha(BLUE, 20),
                    dp(12),
                ),
                radius = dp(12),
                rippleColor = colorWithAlpha(if (danger) RED else BLUE, 28),
            )
            setOnClickListener { onClick() }
            layoutParams = params.apply { height = dp(50) }
        }

    private fun iconButton(symbol: String, description: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(BLUE)
            background = clippedRipple(
                roundedSolid(colorWithAlpha(BLUE, 16), dp(24)),
                dp(24),
                colorWithAlpha(BLUE, 24),
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun divider(): View =
        View(this).apply {
            background = roundedSolid(colorWithAlpha(TEXT_TERTIARY, 24), 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

    private fun softBackground(): Drawable = ColorDrawable(SURFACE)

    private fun glassDrawable(radius: Int = dp(12), alpha: Int = 255, strokeColor: Int = Color.TRANSPARENT): Drawable {
        val fill = roundedSolid(colorWithAlpha(CARD_SURFACE, alpha), radius)
        if (strokeColor == Color.TRANSPARENT) return fill
        val stroke = roundedStroke(strokeColor, radius, 1)
        return LayerDrawable(arrayOf(fill, stroke))
    }

    private fun primaryButtonDrawable(): Drawable {
        val fill = roundedSolid(BLUE, dp(12))
        return clippedRipple(fill, dp(12), colorWithAlpha(ON_PRIMARY, 54))
    }

    private fun segmentedButtonBackground(selected: Boolean, radius: Int): Drawable =
        clippedRipple(
            content = roundedSolid(if (selected) colorWithAlpha(BLUE, 24) else Color.TRANSPARENT, radius),
            radius = radius,
            rippleColor = colorWithAlpha(BLUE, 24),
        )

    private fun clippedRipple(content: Drawable, radius: Int, rippleColor: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            content,
            roundedSolid(CARD_SURFACE, radius),
        )

    private fun roundedSolid(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }

    private fun roundedStroke(color: Int, radius: Int, width: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(width, color)
        }

    private fun Switch.applySwitchStyle() {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled),
        )
        thumbTintList = ColorStateList(
            states,
            intArrayOf(CARD_SURFACE, CARD_SURFACE, colorWithAlpha(CARD_SURFACE, 180)),
        )
        trackTintList = ColorStateList(
            states,
            intArrayOf(GREEN, SEPARATOR, colorWithAlpha(SEPARATOR, 150)),
        )
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun yesNo(value: Boolean): String = if (value) "正常" else "异常"

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun weightedWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun space(value: Int, horizontal: Boolean = false): Space =
        Space(this).apply {
            layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(dp(value), 1)
            } else {
                LinearLayout.LayoutParams(1, dp(value))
            }
        }

    private fun statusBarHeight(): Int =
        systemDimension("status_bar_height").takeIf { it > 0 } ?: dp(28)

    private fun navigationBarHeight(): Int =
        systemDimension("navigation_bar_height").coerceAtLeast(0)

    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val isSystem: Boolean,
    )

    private enum class SettingsPage(val label: String) {
        STATUS("状态"),
        APPS("App"),
        CONTROL("控制"),
        LOGS("日志"),
    }

    private enum class AppFilter(val label: String) {
        SELECTED("已启用"),
        USER("用户 App"),
        ALL("全部"),
    }

    private enum class LogFilter(val label: String) {
        ALL("全部"),
        HOOK("Hook"),
        RELAY("Relay"),
    }

}
