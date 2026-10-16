package com.lookbuddy.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MorningHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerExpandsShowsSupplementaryStatsAndCanCollapse() {
        compose.setContent { MorningHomeScreen() }
        compose.onNodeWithTag("collapsed_stats").assertExists()
        compose.onNodeWithTag("stats_toggle").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithText("本月工作时长").assertIsDisplayed()
        compose.onNodeWithText("今日坐姿").assertIsDisplayed()
        compose.onNodeWithText("今日小憩").assertIsDisplayed()
        compose.onNodeWithText("距周末还有").assertIsDisplayed()
        compose.onNodeWithText("收起").assertDoesNotExist()
        // Taps no longer dismiss an expanded panel.
        compose.onNodeWithTag("stats_toggle").performTouchInput { click() }
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithTag("stats_toggle").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("collapsed_stats").assertExists()
        compose.onNodeWithText("本月工作时长").assertDoesNotExist()
    }

    @Test fun drawerGestureAndSavedStateWork() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MorningHomeScreen() }
        compose.onNodeWithTag("collapsed_stats").performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("expanded_stats").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithTag("expanded_stats").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("collapsed_stats").assertExists()
    }

    @Test fun drawerFollowsFingerBeforeReleaseAndShortSlowDragReturnsToClosed() {
        compose.setContent { MorningHomeScreen(previewDate = java.time.LocalDate.of(2026, 10, 13)) }
        val closedHeight = compose.onNodeWithTag("statistics_sheet").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("stats_toggle").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f))
            advanceEventTime(32)
        }
        val draggedHeight = compose.onNodeWithTag("statistics_sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Panel should track the finger before release", draggedHeight > closedHeight + 30f)
        assertTrue("A partial drag must not jump straight to expanded", draggedHeight < closedHeight + 150f)
        compose.onNodeWithTag("stats_toggle").performTouchInput { advanceEventTime(400); up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("collapsed_stats").assertExists()
    }
}
