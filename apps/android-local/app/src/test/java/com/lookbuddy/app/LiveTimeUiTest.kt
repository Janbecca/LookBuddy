package com.lookbuddy.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDateTime
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LiveTimeUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun homeAutomaticallySwitchesAtSixWithoutNavigation() {
        var now = LocalDateTime.of(2026, 10, 16, 17, 59, 59)
        compose.setContent {
            CompositionLocalProvider(LocalAppNow provides { now }) { MorningHomeScreen() }
        }
        compose.onNodeWithTag("work_mode").assertIsSelected()
        compose.onNodeWithTag("day_background").assertExists()
        compose.runOnIdle { now = now.plusSeconds(2) }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("offwork_mode").assertIsSelected()
        compose.onNodeWithTag("night_background").assertExists()
    }
    @Test fun diaryDefaultsToTodayAndTracksMidnight() {
        var now = LocalDateTime.of(2026, 10, 16, 23, 59, 59)
        compose.setContent {
            CompositionLocalProvider(LocalAppNow provides { now }) { DiaryScreen(1f) }
        }
        compose.onNodeWithText("10月16日 · 周五").assertExists()
        compose.onNodeWithTag("diary_entry_0").assertDoesNotExist()
        compose.runOnIdle { now = now.plusSeconds(2) }
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithText("10月17日 · 周六").assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CompactFortuneLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sevenMoodDaysAreVisibleAtLargeFont() {
        val today = LocalDate.of(2026, 10, 16)
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.5f)) {
                MoodCalendar(today, mapOf(today to Mood.Tired), {}, { true })
            }
        }
        compose.onNodeWithTag("calendar_week").performScrollTo()
        for (days in 0L..6L) compose.onNodeWithTag("mood_day_" + today.minusDays(days)).assertIsDisplayed()
        compose.onNodeWithText("这一周的心情").assertIsDisplayed()
    }
}
