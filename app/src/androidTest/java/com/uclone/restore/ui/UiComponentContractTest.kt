package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.uclone.restore.model.WorkspaceOwnershipReport

class UiComponentContractTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    fun opticalNavigationRendersAsSiblingOfCapturedContent() {
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
                            repeat(6) { index ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .background(if (index % 2 == 0) Color.White else Color(0xFFE2F0FF)),
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
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertTrue(screenshot.width > 0)
        assertTrue(screenshot.height > 0)
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
}
