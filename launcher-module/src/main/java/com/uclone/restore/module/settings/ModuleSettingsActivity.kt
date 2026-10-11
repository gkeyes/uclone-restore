package com.uclone.restore.module.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
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

    // Design system v2 tokens (ModuleTheme.kt); names kept so the builders below read the same.
    private val palette: ModulePalette by lazy { ModulePalette.from(this) }
    private val SURFACE: Int get() = palette.page
    private val CARD_SURFACE: Int get() = palette.card
    private val TEXT_PRIMARY: Int get() = palette.textPrimary
    private val TEXT_SECONDARY: Int get() = palette.textSecondary
    private val TEXT_TERTIARY: Int get() = palette.textTertiary
    private val BLUE: Int get() = palette.accent
    private val ON_PRIMARY: Int get() = palette.onAccent
    private val SEPARATOR: Int get() = palette.hairline

    private lateinit var largeHeader: View
    private lateinit var compactBar: TextView
    private var headerCollapsed = false
    private var lastShownPage: SettingsPage? = null
    private val segmentIndicators = mutableMapOf<LinearLayout, View>()
    private var hookMetric: LinearLayout? = null
    private var targetMetric: LinearLayout? = null
    private var controlMetric: LinearLayout? = null
    private var errorMetric: LinearLayout? = null
    private var targetNavValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()

        launchableApps = queryLaunchableApps()
        selectedPackages = ModuleSettingsStore.allowedPackageSet(this).toMutableSet()
        sanitizeSelectedPackages()
        setContentView(buildRoot())
        applyEdgeToEdge()
        showPage(SettingsPage.STATUS)
        refreshDiagnostics()
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    }

    /** Edge-to-edge: draw behind the bars and pad content with the real insets. */
    @Suppress("DEPRECATION")
    private fun applyEdgeToEdge() {
        val light = !isDarkMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
        window.decorView.requestApplyInsets()
    }

    /** Root: content column plus the compact 17sp title bar that fades in when the large title collapses. */
    private fun buildRoot(): FrameLayout =
        FrameLayout(this).apply {
            setBackgroundColor(SURFACE)
            val content = buildContent()
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            compactBar = TextView(this@ModuleSettingsActivity).apply {
                text = "UClone Module"
                textSize = ModuleType.COLLAPSED_TITLE
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT_PRIMARY)
                gravity = Gravity.CENTER
                alpha = 0f
                visibility = View.INVISIBLE
                background = LayerDrawable(arrayOf(ColorDrawable(colorWithAlpha(SURFACE, 240)), hairlineBottom()))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(compactBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ModuleDimens.COMPACT_BAR)))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars: IntArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val system = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    intArrayOf(system.left, system.top, system.right, system.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                content.setPadding(
                    bars[0] + dp(ModuleDimens.MD),
                    bars[1] + dp(ModuleDimens.SM),
                    bars[2] + dp(ModuleDimens.MD),
                    bars[3] + dp(ModuleDimens.SM),
                )
                compactBar.setPadding(bars[0], bars[1], bars[2], 0)
                compactBar.layoutParams = compactBar.layoutParams.apply { height = bars[1] + dp(ModuleDimens.COMPACT_BAR) }
                insets
            }
        }

    private fun buildContent(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ModuleDimens.MD), statusBarHeight() + dp(ModuleDimens.SM), dp(ModuleDimens.MD), navigationBarHeight() + dp(ModuleDimens.SM))
            background = softBackground()

            largeHeader = headerCard()
            addView(largeHeader, matchWrap())
            addView(space(ModuleDimens.SM))
            addView(tabBar(), matchWrap())
            addView(space(ModuleDimens.SM))

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
                background = roundedSolid(BLUE, dp(ModuleDimens.RADIUS_CONTROL))
            }
            top.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))

            val titleGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            titleGroup.addView(text("UClone Module", ModuleType.LARGE_TITLE, TEXT_PRIMARY, true).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
            })
            headerSubtitle = text("桌面长按入口控制", ModuleType.SUBHEAD, TEXT_SECONDARY, false).apply {
                fontFeatureSettings = "tnum"
            }
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
                setOnCheckedChangeListener { view, checked ->
                    view.performHapticFeedback(ModuleHaptics.toggle(checked))
                    persistHookEnabled(checked)
                }
            }
            top.addView(hookSwitch, LinearLayout.LayoutParams(dp(58), dp(48)))
            addView(top, matchWrap())
        }

    private fun tabBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ModuleDimens.XXS), dp(ModuleDimens.XXS), dp(ModuleDimens.XXS), dp(ModuleDimens.XXS))
            SettingsPage.entries.forEach { page ->
                val item = TextView(this@ModuleSettingsActivity).apply {
                    text = page.label
                    textSize = ModuleType.SUBHEAD
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (page != currentPage) it.performHapticFeedback(ModuleHaptics.segmentTick())
                        showPage(page)
                    }
                    pressable()
                }
                tabButtons[page] = item
                addView(item, LinearLayout.LayoutParams(0, dp(ModuleDimens.TOUCH), 1f))
            }
        }.let(::segmented)

    private fun showPage(page: SettingsPage) {
        val previous = lastShownPage
        currentPage = page
        lastShownPage = page
        updateTabStyles()
        pageContainer.removeAllViews()
        hookMetric = null
        targetMetric = null
        controlMetric = null
        errorMetric = null
        targetNavValue = null
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
        setHeaderCollapsed(false)
        if (previous != null && previous != page) {
            // Shared X axis: 30dp + fade, direction follows the tab order.
            val direction = if (page.ordinal > previous.ordinal) 1f else -1f
            content.alpha = 0f
            content.translationX = dp(ModuleMotion.PAGE_OFFSET_DP) * direction
            content.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(ModuleMotion.SPATIAL)
                .setInterpolator(ModuleMotion.standard)
                .start()
        }
        if (StaggerRegistry.played.add(page.name)) {
            val group: ViewGroup? = when (content) {
                is ScrollView -> content.getChildAt(0) as? ViewGroup
                is ViewGroup -> content
                else -> null
            }
            if (group != null) {
                var order = 0
                for (index in 0 until group.childCount) {
                    val child = group.getChildAt(index)
                    if (child is Space) continue
                    if (order >= ModuleMotion.STAGGER_MAX) break
                    staggerIn(child, order++)
                }
            }
        }
        refreshDiagnostics()
    }

    /** Collapse / expand the large title; the compact 17sp bar and its hairline fade in. */
    private fun setHeaderCollapsed(collapsed: Boolean) {
        if (!::largeHeader.isInitialized || collapsed == headerCollapsed) return
        headerCollapsed = collapsed
        compactBar.visibility = View.VISIBLE
        compactBar.animate()
            .alpha(if (collapsed) 1f else 0f)
            .setDuration(ModuleMotion.EFFECTS)
            .setInterpolator(ModuleMotion.standard)
            .withEndAction { if (!headerCollapsed) compactBar.visibility = View.INVISIBLE }
            .start()
        val parentWidth = (largeHeader.parent as? View)?.width ?: 0
        largeHeader.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullHeight = largeHeader.measuredHeight.coerceAtLeast(1)
        val from = largeHeader.height
        val to = if (collapsed) 0 else fullHeight
        ValueAnimator.ofInt(from, to).apply {
            duration = ModuleMotion.SPATIAL
            interpolator = ModuleMotion.standard
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                largeHeader.layoutParams = largeHeader.layoutParams.apply {
                    height = if (!collapsed && value == to) ViewGroup.LayoutParams.WRAP_CONTENT else value
                }
                largeHeader.alpha = value.toFloat() / fullHeight
            }
            start()
        }
    }

    /** Pages report their scroll; the large title collapses once 56dp of content has scrolled. */
    private fun ScrollView.reportCollapse(): ScrollView = apply {
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            setHeaderCollapsed(scrollY > dp(ModuleDimens.ROW_MIN))
        }
    }

    private fun statusPage(): ScrollView =
        pageScroll().apply {
            val root = pageColumn()
            root.addView(sectionHeader("状态", "模块、权限和目标 App 的当前状态。"))
            root.addView(space(ModuleDimens.SM))
            root.addView(statusSummaryCard(), matchWrap())
            root.addView(space(ModuleDimens.SM))
            root.addView(diagnosticsCard(), matchWrap())
            root.addView(space(ModuleDimens.SM))
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
            row1.addView(metricTile(if (hookEnabled) "可用" else "关闭", "Hook", if (hookEnabled) ModuleTone.SUCCESS else ModuleTone.WARNING).also { hookMetric = it }, weightedWrap())
            row1.addView(space(ModuleDimens.XS, horizontal = true))
            row1.addView(metricTile("${selectedPackages.size} 个", "目标 App", ModuleTone.INFO).also { targetMetric = it }, weightedWrap())
            grid.addView(row1, matchWrap())
            grid.addView(space(ModuleDimens.XS))
            val row2 = LinearLayout(this@ModuleSettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val hookErrors = ModuleSettingsStore.consecutiveHookErrors(this@ModuleSettingsActivity)
            row2.addView(metricTile(yesNo(hasControlPermission()), "CONTROL", if (hasControlPermission()) ModuleTone.SUCCESS else ModuleTone.WARNING).also { controlMetric = it }, weightedWrap())
            row2.addView(space(ModuleDimens.XS, horizontal = true))
            row2.addView(metricTile(hookErrors.toString(), "Hook 异常", if (hookErrors == 0) ModuleTone.SUCCESS else ModuleTone.WARNING).also { errorMetric = it }, weightedWrap())
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
            addView(space(ModuleDimens.XS))
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
                    addView(text("App", ModuleType.TITLE, TEXT_PRIMARY, true))
                    addView(bodyText("为桌面长按菜单启用目标应用。"))
                }
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton("↻", "刷新 App") {
                    refreshApps()
                    Toast.makeText(this@ModuleSettingsActivity, "App 列表已刷新", Toast.LENGTH_SHORT).show()
                })
            }
            addView(header, matchWrap())
            addView(space(ModuleDimens.SM))
            addView(appFilterBar(), matchWrap())
            addView(space(ModuleDimens.SM))

            val listSurface = glassCard().apply {
                setPadding(dp(ModuleDimens.XS), dp(ModuleDimens.XXS), dp(ModuleDimens.XS), dp(ModuleDimens.XXS))
            }
            val scroller = ScrollView(this@ModuleSettingsActivity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }.reportCollapse()
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
            addView(space(ModuleDimens.XS))
            addView(appBottomActionBar(), matchWrap())
            populateAppList()
        }

    private fun appFilterBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ModuleDimens.XXS), dp(ModuleDimens.XXS), dp(ModuleDimens.XXS), dp(ModuleDimens.XXS))
            AppFilter.entries.forEach { filter ->
                val button = TextView(this@ModuleSettingsActivity).apply {
                    text = filter.label
                    textSize = ModuleType.SECTION_HEADER
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    pressable()
                    setOnClickListener {
                        if (filter != currentAppFilter) it.performHapticFeedback(ModuleHaptics.segmentTick())
                        currentAppFilter = filter
                        updateAppFilterStyles()
                        populateAppList()
                    }
                }
                appFilterButtons[filter] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            updateAppFilterStyles()
        }.let(::segmented)

    private fun controlPage(): ScrollView =
        pageScroll().apply {
            val root = pageColumn()
            root.addView(sectionHeader("控制", "Hook 开关、作用域和恢复控制。"))
            root.addView(space(ModuleDimens.SM))
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
                addView(space(ModuleDimens.SM))
                addView(infoStrip("只负责把快捷入口注入桌面。真实备份、恢复和 Root 操作仍由 UClone Restore 执行。"))
            }, matchWrap())
            root.addView(space(ModuleDimens.SM))
            root.addView(glassCard().apply {
                addView(cardTitle("作用域"))
                addView(space(ModuleDimens.XS))
                addView(infoStrip("LSPosed 只勾选 com.miui.home。目标 App 在 App 页选择，不进入作用域。"))
                addView(space(ModuleDimens.XS))
                addView(statusPill("com.miui.home", ModuleTone.INFO))
            }, matchWrap())
            root.addView(space(ModuleDimens.SM))
            root.addView(glassCard().apply {
                addView(cardTitle("恢复"))
                addView(space(ModuleDimens.XS))
                addView(bodyText("Hook 自动禁用后才需要解除。正常情况下不用点。"))
                addView(space(ModuleDimens.SM))
                addView(secondaryButton("解除 Hook 自动禁用", {
                    ModuleSettingsStore.resetAutoDisable(this@ModuleSettingsActivity)
                    Toast.makeText(this@ModuleSettingsActivity, "Hook 熔断计数已清零", Toast.LENGTH_SHORT).show()
                    refreshDiagnostics()
                }, matchWrap()))
            }, matchWrap())
            root.addView(space(ModuleDimens.LG))
            root.addView(primaryButton("保存设置", {
                pageContainer.performHapticFeedback(ModuleHaptics.confirm())
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
                    addView(text("日志", ModuleType.TITLE, TEXT_PRIMARY, true))
                    addView(bodyText("Hook、Relay 事件与诊断。"))
                }
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton("↻", "刷新") { refreshDiagnostics() })
                addView(space(ModuleDimens.XS, horizontal = true))
                addView(iconButton("⧉", "复制") { copyLogs() })
            }
            addView(header, matchWrap())
            addView(space(ModuleDimens.SM))
            addView(logFilterBar(), matchWrap())
            addView(space(ModuleDimens.SM))

            val logSurface = glassCard().apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            val scroller = ScrollView(this@ModuleSettingsActivity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }.reportCollapse()
            val body = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            hookLog = logText()
            relayLog = logText()
            body.addView(hookLog, matchWrap())
            body.addView(space(ModuleDimens.SM))
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
            addView(space(ModuleDimens.SM))
            addView(secondaryButton("清空模块日志", {
                pageContainer.performHapticFeedback(ModuleHaptics.confirm())
                ModuleSettingsStore.clearEvents(this@ModuleSettingsActivity)
                refreshDiagnostics()
                Toast.makeText(this@ModuleSettingsActivity, "模块日志已清空", Toast.LENGTH_SHORT).show()
            }, matchWrap(), danger = true))
        }

    private fun logFilterBar(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ModuleDimens.XXS), dp(ModuleDimens.XXS), dp(ModuleDimens.XXS), dp(ModuleDimens.XXS))
            LogFilter.entries.forEach { filter ->
                val button = TextView(this@ModuleSettingsActivity).apply {
                    text = filter.label
                    textSize = ModuleType.SECTION_HEADER
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    pressable()
                    setOnClickListener {
                        if (filter != currentLogFilter) it.performHapticFeedback(ModuleHaptics.segmentTick())
                        currentLogFilter = filter
                        updateLogFilterStyles()
                        refreshDiagnostics()
                    }
                }
                logFilterButtons[filter] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            updateLogFilterStyles()
        }.let(::segmented)

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
        // Status metrics update in place (tone cross-fade + rolling value) instead of a page rebuild.
        refreshDiagnostics()
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
            val row = appRow(app)
            appListContainer.addView(row, matchWrap())
            if (index < ModuleMotion.STAGGER_MAX) staggerIn(row, index)
        }
        if (apps.isEmpty()) {
            val empty = emptyState(
                title = "没有符合条件的 App",
                message = if (currentAppFilter == AppFilter.SELECTED) {
                    "还没有启用目标 App，切换到“用户 App”开始选择。"
                } else {
                    "没有可启动的 App，点右上角刷新重试。"
                },
                actionLabel = if (currentAppFilter == AppFilter.SELECTED) "查看用户 App" else null,
                onAction = {
                    currentAppFilter = AppFilter.USER
                    updateAppFilterStyles()
                    populateAppList()
                },
            )
            appListContainer.addView(empty, matchWrap())
            staggerIn(empty, 0)
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
            setOnCheckedChangeListener { view, checked ->
                view.performHapticFeedback(ModuleHaptics.toggle(checked))
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
            minimumHeight = dp(ModuleDimens.ROW_MIN)
            setPadding(dp(ModuleDimens.XS), dp(ModuleDimens.XS), dp(ModuleDimens.XXS), dp(ModuleDimens.XS))
            alpha = if (app.isSystem) ModuleAlpha.DISABLED else 1f
            background = clippedRipple(
                content = roundedSolid(Color.TRANSPARENT, dp(ModuleDimens.RADIUS_ROW)),
                radius = dp(ModuleDimens.RADIUS_ROW),
                rippleColor = withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER),
            )
            setOnClickListener {
                if (!app.isSystem) toggle.performClick()
            }
            if (!app.isSystem) pressable(fullWidthRow = true)

            addView(ImageView(this@ModuleSettingsActivity).apply {
                setImageDrawable(app.icon)
                background = roundedSolid(palette.neutral.container, dp(ModuleDimens.RADIUS_SMALL))
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(40), dp(40)))

            val labelGroup = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                val titleLine = LinearLayout(this@ModuleSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(app.label, ModuleType.BODY, TEXT_PRIMARY, true).apply {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (app.isSystem) {
                        addView(space(6, horizontal = true))
                        addView(tinyTag("系统", ModuleTone.WARNING))
                    }
                }
                addView(titleLine, matchWrap())
                addView(text(app.packageName, ModuleType.CAPTION, TEXT_SECONDARY, false).apply {
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
            rows.forEach { row ->
                diagnosticsContainer.addView(diagnosticRow(row.first, row.second, row.third), matchWrap())
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
        updateStatusMetrics()
        updateSelectedCount()
    }

    private fun appBottomActionBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ModuleDimens.SM), dp(ModuleDimens.SM), dp(ModuleDimens.SM), dp(ModuleDimens.SM))
            background = glassDrawable(dp(ModuleDimens.RADIUS_CARD))

            selectedCountText = text("", ModuleType.SUBHEAD, TEXT_SECONDARY, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                fontFeatureSettings = "tnum"
            }
            addView(selectedCountText, matchWrap())
            addView(space(ModuleDimens.XS))
            addView(primaryButton("保存目标 App", {
                pageContainer.performHapticFeedback(ModuleHaptics.confirm())
                saveValues()
                Toast.makeText(this@ModuleSettingsActivity, "目标 App 已保存", Toast.LENGTH_SHORT).show()
                refreshDiagnostics()
            }, matchWrap()))
        }

    private fun updateHeaderSubtitle() {
        if (::headerSubtitle.isInitialized) {
            val status = if (ModuleSettingsStore.isHookEnabled(this)) "已启用" else "已关闭"
            headerSubtitle.text = "$status · ${selectedPackages.size} 个目标"
        }
    }

    private fun updateSelectedCount() {
        if (::selectedCountText.isInitialized) {
            rollText(selectedCountText, "已选择 ${selectedPackages.size} 个 App")
        }
    }

    private fun updateStatusMetrics() {
        val hookEnabled = ModuleSettingsStore.isHookEnabled(this)
        val control = hasControlPermission()
        val hookErrors = ModuleSettingsStore.consecutiveHookErrors(this)
        hookMetric?.let { updateMetric(it, if (hookEnabled) "可用" else "关闭", if (hookEnabled) ModuleTone.SUCCESS else ModuleTone.WARNING) }
        targetMetric?.let { updateMetric(it, "${selectedPackages.size} 个", ModuleTone.INFO) }
        controlMetric?.let { updateMetric(it, yesNo(control), if (control) ModuleTone.SUCCESS else ModuleTone.WARNING) }
        errorMetric?.let { updateMetric(it, hookErrors.toString(), if (hookErrors == 0) ModuleTone.SUCCESS else ModuleTone.WARNING) }
        targetNavValue?.let { rollText(it, "${selectedPackages.size} 个") }
    }

    private fun updateTabStyles() {
        tabButtons.forEach { (page, button) ->
            val selected = page == currentPage
            button.isSelected = selected
            button.contentDescription = if (selected) "${page.label}，当前页面" else "打开${page.label}页面"
            button.setTextColor(if (selected) BLUE else TEXT_SECONDARY)
            button.background = clippedRipple(
                roundedSolid(Color.TRANSPARENT, dp(ModuleDimens.RADIUS_CONTROL - 2)),
                dp(ModuleDimens.RADIUS_CONTROL - 2),
                withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER),
            )
            if (selected) moveSegmentIndicator(button)
        }
    }

    private fun updateAppFilterStyles() {
        appFilterButtons.forEach { (filter, button) ->
            val selected = filter == currentAppFilter
            button.isSelected = selected
            button.contentDescription = if (selected) "${filter.label}筛选，已选中" else "使用${filter.label}筛选"
            button.setTextColor(if (selected) BLUE else TEXT_SECONDARY)
            button.background = clippedRipple(
                roundedSolid(Color.TRANSPARENT, dp(ModuleDimens.RADIUS_CONTROL - 2)),
                dp(ModuleDimens.RADIUS_CONTROL - 2),
                withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER),
            )
            if (selected) moveSegmentIndicator(button)
        }
    }

    private fun updateLogFilterStyles() {
        logFilterButtons.forEach { (filter, button) ->
            val selected = filter == currentLogFilter
            button.isSelected = selected
            button.contentDescription = if (selected) "${filter.label}日志，已选中" else "显示${filter.label}日志"
            button.setTextColor(if (selected) BLUE else TEXT_SECONDARY)
            button.background = clippedRipple(
                roundedSolid(Color.TRANSPARENT, dp(ModuleDimens.RADIUS_CONTROL - 2)),
                dp(ModuleDimens.RADIUS_CONTROL - 2),
                withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER),
            )
            if (selected) moveSegmentIndicator(button)
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
            isVerticalScrollBarEnabled = false
        }.reportCollapse()

    private fun pageColumn(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ModuleDimens.XXS))
        }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ModuleDimens.XXS), 0, dp(ModuleDimens.XXS), 0)
            addView(text(title, ModuleType.TITLE, TEXT_PRIMARY, true))
            addView(bodyText(subtitle))
        }

    /** GroupCard: depth from the card tone only — no border, no shadow. */
    private fun glassCard(compact: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val vertical = if (compact) dp(ModuleDimens.SM) else dp(ModuleDimens.MD)
            setPadding(dp(ModuleDimens.MD), vertical, dp(ModuleDimens.MD), vertical)
            background = glassDrawable()
            elevation = 0f
        }

    private fun cardTitle(value: String): TextView =
        text(value, ModuleType.HEADLINE, TEXT_PRIMARY, true)

    private fun bodyText(value: String): TextView =
        text(value, ModuleType.SUBHEAD, TEXT_SECONDARY, false).apply {
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun logText(): TextView =
        text("", ModuleType.CAPTION, TEXT_SECONDARY, false).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setTextIsSelectable(true)
        }

    private fun infoStrip(value: String): TextView =
        bodyText(value).apply {
            setPadding(dp(ModuleDimens.SM), dp(ModuleDimens.SM - 2), dp(ModuleDimens.SM), dp(ModuleDimens.SM - 2))
            background = roundedSolid(palette.neutral.container, dp(ModuleDimens.RADIUS_CONTROL))
        }

    /** EmptyState: 64dp tonal circle + headline + one subhead + optional action. */
    private fun emptyState(
        title: String,
        message: String,
        actionLabel: String? = null,
        onAction: () -> Unit = {},
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ModuleDimens.MD), dp(ModuleDimens.LG), dp(ModuleDimens.MD), dp(ModuleDimens.LG))
            addView(TextView(this@ModuleSettingsActivity).apply {
                text = "∅"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(palette.info.content)
                background = roundedSolid(palette.info.container, dp(32))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(space(ModuleDimens.SM))
            addView(text(title, ModuleType.HEADLINE, TEXT_PRIMARY, true).apply { gravity = Gravity.CENTER })
            addView(space(ModuleDimens.XXS))
            addView(bodyText(message).apply { gravity = Gravity.CENTER })
            if (actionLabel != null) {
                addView(space(ModuleDimens.SM))
                addView(
                    secondaryButton(
                        actionLabel,
                        onAction,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    ).apply { setPadding(dp(ModuleDimens.LG), 0, dp(ModuleDimens.LG), 0) },
                )
            }
        }

    private fun navigationRow(title: String, subtitle: String, value: String, onClick: () -> Unit): LinearLayout =
        glassCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ModuleDimens.ROW_MIN)
            background = clippedRipple(glassDrawable(), dp(ModuleDimens.RADIUS_CARD), withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER))
            setOnClickListener { onClick() }
            pressable(fullWidthRow = true)
            val label = LinearLayout(this@ModuleSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(cardTitle(title))
                addView(bodyText(subtitle))
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, ModuleType.HEADLINE, TEXT_PRIMARY, true).apply {
                fontFeatureSettings = "tnum"
                if (title == "目标 App") targetNavValue = this
            })
            addView(space(ModuleDimens.XS, horizontal = true))
            addView(text("›", 26f, TEXT_TERTIARY, false))
        }

    private fun metricTile(value: String, label: String, tone: ModuleTone): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val colors = palette.tone(tone)
            setPadding(dp(ModuleDimens.SM), dp(ModuleDimens.SM - 2), dp(ModuleDimens.SM), dp(ModuleDimens.SM - 2))
            background = roundedSolid(colors.container, dp(ModuleDimens.RADIUS_ROW))
            tag = colors
            addView(text(value, ModuleType.METRIC, colors.content, true).apply { fontFeatureSettings = "tnum" })
            addView(text(label, ModuleType.CAPTION, withAlpha(colors.content, 0.8f), true))
        }

    /** Metric change: tone cross-fades (effects 200ms) and the value rolls in from its direction. */
    private fun updateMetric(tile: LinearLayout, value: String, tone: ModuleTone) {
        val from = tile.tag as? ToneColor ?: return
        val to = palette.tone(tone)
        val valueView = tile.getChildAt(0) as? TextView ?: return
        val labelView = tile.getChildAt(1) as? TextView
        rollText(valueView, value)
        if (from == to) return
        tile.tag = to
        val background = tile.background as? GradientDrawable ?: return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ModuleMotion.EFFECTS
            interpolator = ModuleMotion.standard
            val evaluator = android.animation.ArgbEvaluator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                background.setColor(evaluator.evaluate(fraction, from.container, to.container) as Int)
                val content = evaluator.evaluate(fraction, from.content, to.content) as Int
                valueView.setTextColor(content)
                labelView?.setTextColor(withAlpha(content, 0.8f))
            }
            start()
        }
    }

    /** Rolling number: new text slides in upward when the number grows, downward when it shrinks. */
    private fun rollText(view: TextView, newText: String) {
        val old = view.text?.toString().orEmpty()
        if (old == newText) return
        view.text = newText
        if (old.isEmpty() || !view.isAttachedToWindow) return
        val oldNumber = Regex("\\d+").find(old)?.value?.toLongOrNull()
        val newNumber = Regex("\\d+").find(newText)?.value?.toLongOrNull()
        val direction = if (oldNumber != null && newNumber != null && newNumber < oldNumber) -1f else 1f
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = direction * view.height.coerceAtLeast(dp(16)) * 0.6f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ModuleMotion.SPATIAL)
            .setInterpolator(ModuleMotion.standard)
            .start()
    }

    private fun diagnosticRow(label: String, value: String, ok: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ModuleDimens.TOUCH)
            setPadding(0, dp(ModuleDimens.XS), 0, dp(ModuleDimens.XS))
            addView(statusDot(if (ok) palette.success.content else palette.warning.content), LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(space(ModuleDimens.SM, horizontal = true))
            addView(text(label, ModuleType.SUBHEAD, TEXT_SECONDARY, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(value, ModuleType.SUBHEAD, if (ok) TEXT_PRIMARY else palette.warning.content, true).apply {
                gravity = Gravity.END
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
        }

    private fun statusPill(label: String, tone: ModuleTone): TextView =
        TextView(this).apply {
            val colors = palette.tone(tone)
            text = "● $label"
            textSize = ModuleType.SECTION_HEADER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.content)
            gravity = Gravity.CENTER
            setPadding(dp(ModuleDimens.SM), 0, dp(ModuleDimens.SM), 0)
            background = roundedSolid(colors.container, dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
        }

    private fun tinyTag(label: String, tone: ModuleTone): TextView =
        TextView(this).apply {
            val colors = palette.tone(tone)
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.content)
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, dp(7), 0)
            background = roundedSolid(colors.container, dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22))
        }

    private fun statusDot(color: Int): View =
        View(this).apply {
            background = roundedSolid(color, dp(4))
        }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        primaryButton(label, onClick, matchWrap())

    /** Primary: filled accent, 52dp, radius 16. */
    private fun primaryButton(label: String, onClick: () -> Unit, params: LinearLayout.LayoutParams): TextView =
        TextView(this).apply {
            text = label
            textSize = ModuleType.BODY
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(ON_PRIMARY)
            background = clippedRipple(
                roundedSolid(BLUE, dp(ModuleDimens.RADIUS_BUTTON)),
                dp(ModuleDimens.RADIUS_BUTTON),
                withAlpha(ON_PRIMARY, 0.2f),
            )
            setOnClickListener { onClick() }
            pressable()
            layoutParams = params.apply { height = dp(ModuleDimens.PRIMARY_BUTTON) }
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): TextView =
        secondaryButton(label, onClick, matchWrap(), danger = false)

    /** Secondary = tonal (accent 12%); destructive = danger tone. */
    private fun secondaryButton(
        label: String,
        onClick: () -> Unit,
        params: LinearLayout.LayoutParams,
        danger: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = ModuleType.BODY
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(if (danger) palette.danger.content else BLUE)
            background = clippedRipple(
                content = roundedSolid(
                    if (danger) palette.danger.container else palette.accentTonal,
                    dp(ModuleDimens.RADIUS_BUTTON),
                ),
                radius = dp(ModuleDimens.RADIUS_BUTTON),
                rippleColor = withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER),
            )
            setOnClickListener { onClick() }
            pressable()
            layoutParams = params.apply { height = dp(ModuleDimens.PRIMARY_BUTTON) }
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
                roundedSolid(palette.accentTonal, dp(ModuleDimens.TOUCH / 2)),
                dp(ModuleDimens.TOUCH / 2),
                withAlpha(palette.onSurface, ModuleAlpha.STATE_LAYER),
            )
            setOnClickListener { onClick() }
            pressable()
            layoutParams = LinearLayout.LayoutParams(dp(ModuleDimens.TOUCH), dp(ModuleDimens.TOUCH))
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun softBackground(): Drawable = ColorDrawable(SURFACE)

    private fun glassDrawable(radius: Int = dp(ModuleDimens.RADIUS_CARD)): Drawable =
        roundedSolid(CARD_SURFACE, radius)

    /** 1px hairline along the bottom edge (compact title bar). */
    private fun hairlineBottom(): Drawable =
        object : Drawable() {
            private val paint = android.graphics.Paint().apply { color = SEPARATOR }
            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                canvas.drawRect(b.left.toFloat(), (b.bottom - 1).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
            }
            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

    /**
     * Segmented control: neutral track (radius 14) with a card-colored selection indicator that
     * slides between segments on the spatial curve.
     */
    private fun segmented(row: LinearLayout): View =
        FrameLayout(this).apply {
            background = roundedSolid(palette.neutral.container, dp(ModuleDimens.RADIUS_CONTROL))
            val indicator = View(this@ModuleSettingsActivity).apply {
                background = roundedSolid(palette.raised, dp(ModuleDimens.RADIUS_CONTROL - 2))
                elevation = if (palette.isDark) 0f else dp(1).toFloat()
                visibility = View.INVISIBLE
            }
            addView(indicator, FrameLayout.LayoutParams(0, 0))
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.background = null
            segmentIndicators[row] = indicator
            row.post {
                (0 until row.childCount).map(row::getChildAt).firstOrNull { it.isSelected }?.let(::moveSegmentIndicator)
            }
        }

    private fun moveSegmentIndicator(button: View) {
        val row = button.parent as? LinearLayout ?: return
        val indicator = segmentIndicators[row] ?: return
        if (button.width == 0) {
            button.post { moveSegmentIndicator(button) }
            return
        }
        val params = indicator.layoutParams
        if (params.width != button.width || params.height != button.height) {
            params.width = button.width
            params.height = button.height
            indicator.layoutParams = params
        }
        val targetX = button.left.toFloat()
        val targetY = button.top.toFloat()
        if (indicator.visibility != View.VISIBLE) {
            indicator.translationX = targetX
            indicator.translationY = targetY
            indicator.visibility = View.VISIBLE
            return
        }
        indicator.animate()
            .translationX(targetX)
            .translationY(targetY)
            .setDuration(ModuleMotion.SPATIAL)
            .setInterpolator(ModuleMotion.standard)
            .start()
    }

    /** Staggered entrance: rise 16dp + fade, 26ms apart. */
    private fun staggerIn(view: View, index: Int) {
        view.alpha = 0f
        view.translationY = dp(ModuleMotion.STAGGER_OFFSET_DP).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * ModuleMotion.STAGGER_STEP)
            .setDuration(ModuleMotion.SPATIAL)
            .setInterpolator(ModuleMotion.standard)
            .start()
    }

    /** Press feedback: 100ms press-in to 0.965 (rows 0.985), springy 380ms release. Clicks still fire. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressable(fullWidthRow: Boolean = false) {
        val pressed = if (fullWidthRow) ModuleMotion.PRESSED_SCALE_ROW else ModuleMotion.PRESSED_SCALE
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(pressed)
                    .scaleY(pressed)
                    .setStartDelay(0)
                    .setDuration(ModuleMotion.PRESS_IN)
                    .setInterpolator(ModuleMotion.standard)
                    .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(0)
                    .setDuration(ModuleMotion.PRESS_RELEASE)
                    .setInterpolator(ModuleMotion.release)
                    .start()
            }
            false
        }
    }

    private fun clippedRipple(content: Drawable, radius: Int, rippleColor: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            content,
            roundedSolid(Color.WHITE, radius),
        )

    private fun roundedSolid(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }

    private fun Switch.applySwitchStyle() {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled),
        )
        thumbTintList = ColorStateList(
            states,
            intArrayOf(Color.WHITE, Color.WHITE, withAlpha(Color.WHITE, 0.7f)),
        )
        trackTintList = ColorStateList(
            states,
            intArrayOf(BLUE, withAlpha(palette.onSurface, 0.16f), withAlpha(palette.onSurface, 0.08f)),
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

/** Pages already staggered in this process (entrance plays once per page per process). */
private object StaggerRegistry {
    val played = mutableSetOf<String>()
}
