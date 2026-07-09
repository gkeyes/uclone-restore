package com.uclone.restore.module.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
    private lateinit var appListContainer: LinearLayout
    private lateinit var diagnosticsContainer: LinearLayout
    private lateinit var hookLog: TextView
    private lateinit var relayLog: TextView
    private lateinit var selectedCountText: TextView
    private val appChecks = mutableMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = SURFACE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildContent())
        loadValues()
        refreshDiagnostics()
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
            background = softBackground()
        }

        root.addView(headerCard(), matchWrap())
        root.addView(space(12))
        root.addView(diagnosticsCard(), matchWrap())
        root.addView(space(12))
        root.addView(appPickerCard(), matchWrap())
        root.addView(space(12))
        root.addView(scopeCard(), matchWrap())
        root.addView(space(12))
        root.addView(logCard(), matchWrap())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun headerCard(): LinearLayout =
        glassCard().apply {
            val top = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val mark = TextView(this@ModuleSettingsActivity).apply {
                text = "U"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = roundedGradient(intArrayOf(BLUE, CYAN), dp(18))
            }
            top.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))

            val titleGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            titleGroup.addView(text("UClone Module", 26f, TEXT_PRIMARY, true))
            titleGroup.addView(text("桌面长按入口控制", 13f, TEXT_SECONDARY, false))
            top.addView(titleGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            hookSwitch = Switch(this@ModuleSettingsActivity).apply {
                text = ""
                minWidth = dp(56)
            }
            top.addView(hookSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(top, matchWrap())

            addView(space(12))
            val status = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            status.addView(statusPill("Hook", GREEN))
            status.addView(space(8, horizontal = true))
            status.addView(statusPill("com.miui.home", BLUE))
            addView(status, matchWrap())
        }

    private fun scopeCard(): LinearLayout =
        glassCard().apply {
            val header = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(cardTitle("控制与作用域"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(pillButton("保存", prominent = true) {
                saveValues()
                Toast.makeText(this@ModuleSettingsActivity, "设置已保存", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            })
            addView(header, matchWrap())

            addView(space(10))
            addView(infoStrip("LSPosed 只勾选 com.miui.home；目标 App 在本页选择，不放进作用域。"))
            addView(space(10))
            addView(pillButton("解除 Hook 自动禁用") {
                ModuleSettingsStore.resetAutoDisable(this@ModuleSettingsActivity)
                Toast.makeText(this@ModuleSettingsActivity, "Hook 熔断计数已清零", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }

    private fun appPickerCard(): LinearLayout =
        glassCard().apply {
            val header = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            titleGroup.addView(cardTitle("允许的 App"))
            selectedCountText = bodyText("已选择 0 个 App")
            titleGroup.addView(selectedCountText)
            header.addView(titleGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(pillButton("刷新") { populateAppList() })
            addView(header, matchWrap())

            addView(space(12))
            val controls = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pillButton("选择用户 App", prominent = true) {
                    appChecks.forEach { (pkg, check) ->
                        check.isChecked = pkg != packageName && pkg != ModuleConstants.UCLONE_PACKAGE && !isSystemPackage(pkg)
                    }
                    updateSelectedCount()
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(space(8, horizontal = true))
                addView(pillButton("清空") {
                    appChecks.values.forEach { it.isChecked = false }
                    updateSelectedCount()
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
            }
            addView(controls, matchWrap())

            addView(space(12))
            val listSurface = ScrollView(this@ModuleSettingsActivity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                background = roundedSolid(Color.argb(118, 255, 255, 255), dp(18))
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            appListContainer = LinearLayout(this@ModuleSettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            listSurface.addView(appListContainer, matchWrap())
            addView(listSurface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)))
        }

    private fun diagnosticsCard(): LinearLayout =
        glassCard().apply {
            val header = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(cardTitle("运行状态"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(pillButton("刷新") { refreshDiagnostics() })
            addView(header, matchWrap())
            addView(space(10))
            diagnosticsContainer = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(diagnosticsContainer, matchWrap())
        }

    private fun logCard(): LinearLayout =
        glassCard().apply {
            addView(cardTitle("最近事件"))
            addView(space(10))
            addView(logBlock("Hook") { hookLog = it }, matchWrap())
            addView(space(8))
            addView(logBlock("Relay") { relayLog = it }, matchWrap())
        }

    private fun loadValues() {
        hookSwitch.isChecked = ModuleSettingsStore.isHookEnabled(this)
        populateAppList()
    }

    private fun saveValues() {
        ModuleSettingsStore.setHookEnabled(this, hookSwitch.isChecked)
        ModuleSettingsStore.setAllowedPackageText(
            this,
            appChecks.filterValues { it.isChecked }.keys.sorted().joinToString("\n"),
        )
        updateSelectedCount()
    }

    private fun populateAppList() {
        appChecks.clear()
        appListContainer.removeAllViews()
        val selected = ModuleSettingsStore.allowedPackageSet(this)
        val apps = queryLaunchableApps()
        apps.forEachIndexed { index, app ->
            val row = appRow(app, app.packageName in selected)
            appListContainer.addView(row, matchWrap())
            if (index != apps.lastIndex) {
                appListContainer.addView(divider(), matchWrap())
            }
        }
        if (apps.isEmpty()) {
            appListContainer.addView(bodyText("未读取到可启动 App"))
        }
        updateSelectedCount()
    }

    private fun appRow(app: LaunchableApp, checked: Boolean): LinearLayout {
        val check = CheckBox(this).apply {
            isChecked = checked && !app.isSystem
            isEnabled = !app.isSystem
            buttonTintList = android.content.res.ColorStateList.valueOf(BLUE)
            setOnCheckedChangeListener { _, _ -> updateSelectedCount() }
        }
        appChecks[app.packageName] = check

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            alpha = if (app.isSystem) 0.52f else 1f
            foreground = selectableItemBackground()
            setOnClickListener {
                if (!app.isSystem) check.isChecked = !check.isChecked
            }

            addView(ImageView(this@ModuleSettingsActivity).apply {
                setImageDrawable(app.icon)
                background = roundedSolid(Color.WHITE, dp(12))
                clipToOutline = true
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }, LinearLayout.LayoutParams(dp(38), dp(38)))

            val labelGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                val titleLine = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(app.label, 15f, TEXT_PRIMARY, true).apply {
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (app.isSystem) {
                        addView(space(6, horizontal = true))
                        addView(tinyTag("系统", ORANGE))
                    }
                }
                addView(titleLine, matchWrap())
                addView(text(app.packageName, 11f, TEXT_TERTIARY, false).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            }
            addView(labelGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        if (::diagnosticsContainer.isInitialized) {
            diagnosticsContainer.removeAllViews()
            val rows = listOf(
                Triple("UClone", packageVersion(ModuleConstants.UCLONE_PACKAGE), isPackageInstalled(ModuleConstants.UCLONE_PACKAGE)),
                Triple("同签名", yesNo(sameSignature()), sameSignature()),
                Triple("CONTROL 权限", yesNo(hasControlPermission()), hasControlPermission()),
                Triple("ActionService", yesNo(hasUCloneService()), hasUCloneService()),
                Triple("RelayProvider", yesNo(hasRelayProvider()), hasRelayProvider()),
                Triple("Hook 自动禁用", yesNo(ModuleSettingsStore.isAutoDisabled(this)), !ModuleSettingsStore.isAutoDisabled(this)),
                Triple("连续 Hook 异常", ModuleSettingsStore.consecutiveHookErrors(this).toString(), ModuleSettingsStore.consecutiveHookErrors(this) == 0),
                Triple("目标 App", "${selectedPackageCount()} 个", selectedPackageCount() > 0),
            )
            rows.forEachIndexed { index, row ->
                diagnosticsContainer.addView(diagnosticRow(row.first, row.second, row.third), matchWrap())
                if (index != rows.lastIndex) diagnosticsContainer.addView(divider(), matchWrap())
            }
        }
        hookLog.text = ModuleSettingsStore.hookEvents(this).ifBlank { "暂无" }
        relayLog.text = ModuleSettingsStore.relayEvents(this).ifBlank { "暂无" }
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        if (::selectedCountText.isInitialized) {
            selectedCountText.text = "已选择 ${appChecks.values.count { it.isChecked }} 个 App"
        }
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

    private fun selectedPackageCount(): Int =
        ModuleSettingsStore.allowedPackageSet(this).size

    private fun isSystemPackage(packageName: String): Boolean =
        runCatching {
            val flags = packageManager.getApplicationInfo(packageName, 0).flags
            flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)

    private fun glassCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = glassDrawable()
            elevation = dp(2).toFloat()
        }

    private fun cardTitle(text: String): TextView =
        text(text, 17f, TEXT_PRIMARY, true)

    private fun bodyText(text: String): TextView =
        text(text, 13f, TEXT_SECONDARY, false).apply {
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun infoStrip(text: String): TextView =
        bodyText(text).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedSolid(Color.argb(118, 255, 255, 255), dp(16))
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
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
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        }

    private fun statusDot(color: Int): View =
        View(this).apply {
            background = roundedSolid(color, dp(5))
        }

    private fun logBlock(title: String, bindBody: (TextView) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedSolid(Color.argb(112, 255, 255, 255), dp(16))
            addView(text(title, 13f, TEXT_SECONDARY, true))
            addView(space(6))
            val scroller = ScrollView(this@ModuleSettingsActivity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }
            val body = TextView(this@ModuleSettingsActivity).apply {
                textSize = 12f
                setTextColor(TEXT_TERTIARY)
                setLineSpacing(dp(2).toFloat(), 1.0f)
            }
            bindBody(body)
            scroller.addView(body, matchWrap())
            addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
        }

    private fun pillButton(label: String, prominent: Boolean = false, onClick: () -> Unit): TextView =
        pillButton(label, prominent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)), onClick)

    private fun pillButton(
        label: String,
        prominent: Boolean = false,
        params: LinearLayout.LayoutParams,
        onClick: () -> Unit,
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (prominent) Color.WHITE else BLUE)
            background = if (prominent) roundedGradient(intArrayOf(BLUE, CYAN), dp(22)) else glassDrawable(dp(22))
            foreground = selectableItemBackground()
            setOnClickListener { onClick() }
            layoutParams = params
        }

    private fun divider(): View =
        View(this).apply {
            background = roundedSolid(colorWithAlpha(TEXT_TERTIARY, 28), 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }

    private fun softBackground(): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(248, 251, 255), Color.rgb(239, 246, 255), Color.rgb(246, 248, 252)),
        )

    private fun glassDrawable(radius: Int = dp(24)): Drawable {
        val fill = roundedSolid(Color.argb(184, 255, 255, 255), radius)
        val stroke = roundedStroke(Color.argb(178, 255, 255, 255), radius, 1)
        val shadow = roundedSolid(Color.argb(18, 0, 0, 0), radius)
        return LayerDrawable(arrayOf(shadow, fill, stroke)).apply {
            setLayerInset(0, 0, dp(2), 0, 0)
        }
    }

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

    private fun roundedGradient(colors: IntArray, radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = radius.toFloat()
        }

    private fun selectableItemBackground(): Drawable? {
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val array = obtainStyledAttributes(attrs)
        return array.getDrawable(0).also { array.recycle() }
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun space(value: Int, horizontal: Boolean = false): Space =
        Space(this).apply {
            layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(dp(value), 1)
            } else {
                LinearLayout.LayoutParams(1, dp(value))
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val isSystem: Boolean,
    )

    private companion object {
        private val SURFACE = Color.rgb(245, 247, 251)
        private val TEXT_PRIMARY = Color.rgb(18, 24, 38)
        private val TEXT_SECONDARY = Color.rgb(88, 96, 112)
        private val TEXT_TERTIARY = Color.rgb(130, 138, 153)
        private val BLUE = Color.rgb(0, 122, 255)
        private val CYAN = Color.rgb(90, 200, 250)
        private val GREEN = Color.rgb(52, 199, 89)
        private val ORANGE = Color.rgb(255, 149, 0)
    }
}
