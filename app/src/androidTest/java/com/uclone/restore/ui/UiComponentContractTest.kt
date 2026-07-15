package com.uclone.restore.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.WorkspaceOwnershipReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class UiComponentContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topLevelHeaderUsesLauncherIconAndKeepsTaskActionAccessible() {
        var taskActionClicks = 0
        composeRule.setContent {
            UCloneTheme {
                TopLevelHeader(
                    title = "UClone",
                    description = "系统状态与常用 App",
                    taskActive = true,
                    onOpenHistory = { taskActionClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("uclone_top_level_header").assertExists()
        composeRule.onNodeWithTag("uclone_brand_icon").assertExists()
        composeRule.onNodeWithContentDescription("UClone 桌面图标").assertExists()
        composeRule.onNodeWithText("C").assertDoesNotExist()
        val brandImage = composeRule.onNodeWithTag("uclone_brand_icon").captureToImage()
        assertTrue("launcher bitmap must contain real image detail", distinctSampledColorCount(brandImage) > 8)
        composeRule.onNodeWithText("UClone").assertExists()
        composeRule.onNodeWithText("系统状态与常用 App").assertExists()
        composeRule.onNodeWithContentDescription("查看当前任务")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, taskActionClicks) }
    }

    @Test
    fun topLevelHeaderScrollsAwayWithTopLevelContent() {
        lateinit var scrollState: LazyListState
        composeRule.setContent {
            UCloneTheme {
                scrollState = rememberLazyListState()
                ScrollableTopLevelContent(
                    modifier = Modifier
                        .width(360.dp)
                        .height(240.dp)
                        .testTag("top_level_scroll_content"),
                    contentPadding = PaddingValues(),
                    state = scrollState,
                    header = {
                        TopLevelHeader(
                            title = "历史",
                            description = "已接受的业务任务与执行结果",
                        )
                    },
                ) {
                    items(20) { index ->
                        Text(
                            "任务 $index",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("uclone_top_level_header").assertExists()
        composeRule.onNodeWithTag("top_level_scroll_content").performTouchInput { swipeUp() }
        composeRule.runOnIdle {
            assertTrue(
                "top-level header must scroll inside its content list",
                scrollState.firstVisibleItemIndex > 0 || scrollState.firstVisibleItemScrollOffset > 0,
            )
            assertTrue(
                "top-level header must not remain visible after the list scrolls",
                scrollState.layoutInfo.visibleItemsInfo.none { it.key == "top_level_header" },
            )
        }
    }

    @Test
    fun responsiveRowsStackAtOnePointThreeFontScale() {
        assertResponsiveRowsStack(fontScale = 1.3f)
    }

    @Test
    fun responsiveRowsRemainStackedAtTwoPointZeroFontScale() {
        assertResponsiveRowsStack(fontScale = 2f)
    }

    @Test
    fun bottomNavigationExposesFiveTabsAndSelectedState() {
        composeRule.setContent {
            UCloneTheme {
                var destination by remember { mutableStateOf(Destination.HOME) }
                Box(Modifier.width(390.dp).height(100.dp)) {
                    FloatingTabBar(
                        destination = destination,
                        onSelect = { destination = it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("uclone_bottom_navigation").assertHeightIsEqualTo(60.dp)
        topLevelDestinations.forEach { destination ->
            composeRule.onNodeWithTag("uclone_nav_${destination.name}")
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithTag("uclone_nav_HOME").assertIsSelected()
        composeRule.onNodeWithTag("uclone_nav_DIAGNOSTICS").assertDoesNotExist()
        composeRule.onNodeWithTag("uclone_nav_APPS").performClick().assertIsSelected()
    }

    @Test
    fun bottomNavigationKeepsAllLabelsInsideAtTwoPointZeroFontScale() {
        composeRule.setContent {
            UCloneTheme {
                val deviceDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = deviceDensity.density,
                        fontScale = 2f,
                    ),
                ) {
                    Box(Modifier.width(390.dp).height(100.dp)) {
                        FloatingTabBar(
                            destination = Destination.HOME,
                            onSelect = {},
                        )
                    }
                }
            }
        }

        val bar = composeRule.onNodeWithTag("uclone_bottom_navigation")
            .assertHeightIsEqualTo(60.dp)
            .fetchSemanticsNode()
            .boundsInRoot
        topLevelDestinations.forEach { destination ->
            val label = composeRule
                .onNodeWithText(destination.label, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("${destination.label} must remain inside the 60dp bar", label.top >= bar.top && label.bottom <= bar.bottom)
            composeRule.onNodeWithTag("uclone_nav_${destination.name}")
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun opticalNavigationRendersAsSiblingOfCapturedContent() {
        val invertBackdrop = mutableStateOf(false)
        composeRule.setContent {
            UCloneTheme {
                GlassBackdropHost(
                    modifier = Modifier.fillMaxSize(),
                    background = {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF2F2F7))
                                .padding(16.dp),
                        ) {
                            repeat(20) { index ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .background(
                                            if ((index + if (invertBackdrop.value) 1 else 0) % 2 == 0) {
                                                Color.White
                                            } else {
                                                Color(0xFFB9DCFF)
                                            },
                                        ),
                                )
                            }
                        }
                    },
                    overlay = {
                        FloatingTabBar(
                            destination = Destination.HOME,
                            onSelect = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithTag("uclone_bottom_navigation").assertHeightIsEqualTo(60.dp)
        composeRule.waitForIdle()
        val barNode = composeRule.onNodeWithTag("uclone_bottom_navigation").fetchSemanticsNode()
        val barPosition = barNode.positionOnScreen
        val barSize = barNode.size
        val before = takeDeviceScreenshot()
        composeRule.runOnIdle { invertBackdrop.value = true }
        composeRule.waitForIdle()
        val after = takeDeviceScreenshot()
        val itemWidth = barSize.width.toFloat() / topLevelDestinations.size
        val samplePoints = buildList {
            repeat(topLevelDestinations.lastIndex) { index ->
                val x = (barPosition.x + itemWidth * (index + 1)).roundToInt()
                add(x to (barPosition.y + barSize.height * 0.2f).roundToInt())
                add(x to (barPosition.y + barSize.height * 0.5f).roundToInt())
                add(x to (barPosition.y + barSize.height * 0.8f).roundToInt())
            }
        }
        val changedGlassSamples = samplePoints.count { (x, y) ->
            val safeX = x.coerceIn(0, before.width - 1)
            val safeY = y.coerceIn(0, before.height - 1)
            before.getPixel(safeX, safeY) != after.getPixel(safeX, safeY)
        }
        assertTrue(
            "glass pixels must respond to the captured background",
            changedGlassSamples >= samplePoints.size / 2,
        )
        assertTrue(
            "glass surface must render sampled background detail",
            distinctSampledColorCount(
                image = after,
                left = barPosition.x.roundToInt(),
                top = barPosition.y.roundToInt(),
                right = (barPosition.x + barSize.width).roundToInt(),
                bottom = (barPosition.y + barSize.height).roundToInt(),
            ) > 8,
        )
    }

    @Test
    fun controlsKeepTouchSizeDisabledStateAndDangerSemantics() {
        composeRule.setContent {
            UCloneTheme {
                GlassBackdropHost(
                    modifier = Modifier.fillMaxSize(),
                    background = {
                        Column {
                            CompactActionButton(
                                text = "删除",
                                onClick = {},
                                modifier = Modifier.testTag("danger_action"),
                                danger = true,
                            )
                            CompactActionButton(
                                text = "不可用",
                                onClick = {},
                                modifier = Modifier.testTag("disabled_action"),
                                enabled = false,
                                primary = true,
                            )
                            UtilityIconButton(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索",
                                onClick = {},
                                modifier = Modifier.testTag("toolbar_control"),
                                framed = true,
                            )
                            DialogActionButton(
                                text = "确认删除",
                                onClick = {},
                                danger = true,
                                modifier = Modifier.testTag("danger_dialog_action"),
                            )
                        }
                    },
                    overlay = {},
                )
            }
        }

        composeRule.onNodeWithTag("danger_action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "危险操作"))
        composeRule.onNodeWithTag("danger_dialog_action")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "危险操作"))
        composeRule.onNodeWithTag("disabled_action")
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("toolbar_control")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun ownershipSummaryKeepsCountsCapacityTimeAndCanonicalPathVisible() {
        composeRule.setContent {
            UCloneTheme {
                WorkspaceOwnershipSummary(
                    WorkspaceOwnershipReport(
                        canonicalRoot = "/data/adb/uclone",
                        totalEntries = 22513,
                        nonRootEntries = 18,
                        totalSizeKb = 4096,
                        scannedAt = 1_789_632_000_000,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("总项数 22513 · 非 root 18").assertExists()
        composeRule.onNodeWithText("容量 4.0 MB · 扫描时间", substring = true).assertExists()
        composeRule.onNodeWithText("规范路径：/data/adb/uclone").assertExists()
    }

    private fun assertResponsiveRowsStack(fontScale: Float) {
        composeRule.setContent {
            UCloneTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                    Column(Modifier.width(320.dp)) {
                        InfoRow("主系统 user0", "已安装 UID 10332")
                        ToolRow(
                            title = "生成恢复审计包",
                            description = "只读采集文件、权限与上下文证据，不修改数据。",
                            actionLabel = "检查",
                            onClick = {},
                            showDivider = false,
                        )
                        ResponsiveSwitchRow(
                            label = "DE Device Protected 数据",
                            checked = false,
                            onCheckedChange = {},
                        )
                        InstallToolRow(
                            title = "安装并同步到另一侧",
                            description = "安装后同步当前数据、权限与 AppOps；长说明必须完整换行。",
                            onClick = {},
                        )
                        CurrentTaskCard(
                            UiState(
                                currentTask = TaskProgress(
                                    task = responsiveTask(
                                        packageName = "com.example.really.long.package.name",
                                        status = TaskStatus.RUNNING,
                                        finishedAt = null,
                                        currentStage = TaskStage.SOURCE_PREPARE,
                                    ),
                                ),
                            ),
                        )
                        HistoryTaskRow(
                            task = responsiveTask(
                                packageName = "user10",
                                status = TaskStatus.SUCCESS_WITH_WARNINGS,
                                finishedAt = 1_789_632_051_000L,
                            ),
                            expanded = false,
                            first = true,
                            last = true,
                            showDivider = false,
                            selectedPackage = null,
                            onToggle = {},
                            onSelectPackage = {},
                            modifier = Modifier.testTag("responsive_history_row"),
                        )
                    }
                }
            }
        }

        val infoLabel = composeRule.onNodeWithText("主系统 user0").fetchSemanticsNode().boundsInRoot
        val infoValue = composeRule.onNodeWithText("已安装 UID 10332").fetchSemanticsNode().boundsInRoot
        val toolTitle = composeRule.onNodeWithText("生成恢复审计包").fetchSemanticsNode().boundsInRoot
        val toolDescription = composeRule
            .onNodeWithText("只读采集文件、权限与上下文证据，不修改数据。")
            .fetchSemanticsNode()
            .boundsInRoot
        val toolActionNode = composeRule.onNodeWithText("检查")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode()
        val toolAction = toolActionNode.boundsInRoot
        val switchLabel = composeRule.onNodeWithText("DE Device Protected 数据").fetchSemanticsNode().boundsInRoot
        val switchControl = composeRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode()
            .boundsInRoot
        val installDescription = composeRule
            .onNodeWithText("安装后同步当前数据、权限与 AppOps；长说明必须完整换行。")
            .fetchSemanticsNode()
            .boundsInRoot
        val installAction = composeRule.onNodeWithText("执行")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode()
            .boundsInRoot
        val currentSummary = composeRule
            .onNodeWithText("推送到分身 · 执行中")
            .fetchSemanticsNode()
            .boundsInRoot
        val currentStatus = composeRule.onNodeWithText("准备源数据").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("responsive_history_row")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        val historyTime = composeRule
            .onNodeWithText("2026", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val historyStatus = composeRule
            .onNodeWithText("完成但有警告", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val historyDuration = composeRule
            .onNodeWithText("51.0 秒", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(infoValue.top >= infoLabel.bottom)
        assertTrue(toolDescription.top >= toolTitle.bottom)
        assertTrue(toolAction.top >= toolDescription.bottom)
        assertTrue(switchControl.top >= switchLabel.bottom)
        assertTrue(installAction.top >= installDescription.bottom)
        assertTrue(currentStatus.top >= currentSummary.bottom)
        assertTrue(historyStatus.top >= historyTime.bottom)
        assertTrue(historyDuration.top >= historyStatus.bottom)
    }

    private fun responsiveTask(
        packageName: String,
        status: TaskStatus,
        finishedAt: Long?,
        currentStage: TaskStage? = null,
    ) = TaskRecord(
        id = 1L,
        requestId = "responsive-layout",
        packageName = packageName,
        type = TaskType.PUSH_MAIN_TO_CLONE,
        startedAt = 1_789_632_000_000L,
        finishedAt = finishedAt,
        status = status,
        logPath = "/data/adb/uclone/logs/responsive-layout.log",
        message = status.name,
        currentStage = currentStage,
    )

    private fun distinctSampledColorCount(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        val colors = mutableSetOf<Int>()
        val xStep = (pixels.width / 12).coerceAtLeast(1)
        val yStep = (pixels.height / 12).coerceAtLeast(1)
        var y = 0
        while (y < pixels.height) {
            var x = 0
            while (x < pixels.width) {
                colors += pixels[x, y].toArgb()
                x += xStep
            }
            y += yStep
        }
        return colors.size
    }

    private fun takeDeviceScreenshot(): Bitmap {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
        return screenshot
    }

    private fun distinctSampledColorCount(
        image: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int {
        val safeLeft = left.coerceIn(0, image.width - 1)
        val safeTop = top.coerceIn(0, image.height - 1)
        val safeRight = right.coerceIn(safeLeft + 1, image.width)
        val safeBottom = bottom.coerceIn(safeTop + 1, image.height)
        val colors = mutableSetOf<Int>()
        val xStep = ((safeRight - safeLeft) / 12).coerceAtLeast(1)
        val yStep = ((safeBottom - safeTop) / 12).coerceAtLeast(1)
        var y = safeTop
        while (y < safeBottom) {
            var x = safeLeft
            while (x < safeRight) {
                colors += image.getPixel(x, y)
                x += xStep
            }
            y += yStep
        }
        return colors.size
    }
}
