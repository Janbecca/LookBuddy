package com.lookbuddy.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MoodCalendarTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 10, 13)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearRecords() {
        context.getSharedPreferences("lookbuddy_moods", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun calendarDismissWithoutChoosingDoesNotWriteAndReopensOnSelection() {
        compose.setContent { MorningHomeScreen(previewDate = date) }
        compose.onNodeWithContentDescription("心情日历").performClick()
        compose.onNodeWithText("忙了一天，心里还好吗？").assertIsDisplayed()
        compose.onNodeWithTag("daily_fortune").assertDoesNotExist()
        compose.onNodeWithTag("calendar_close").performClick()
        assertNull(MoodStore(context).read(date))
        compose.onNodeWithContentDescription("心情日历").performClick()
        compose.onNodeWithTag("mood_Tired").assertIsDisplayed()
    }

    @Test fun choosingTiredPersistsShowsFortuneAndCanChangeWithoutChangingFortune() {
        compose.setContent { MorningHomeScreen(previewDate = date) }
        compose.onNodeWithContentDescription("心情日历").performClick()
        compose.onNodeWithTag("mood_Tired").performClick()
        compose.waitUntil(10_000) { MoodStore(context).read(date) == Mood.Tired }
        // Disk commit precedes the IO coroutine resuming and scheduling the flip.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeBy(32)
            runCatching { compose.onNodeWithText("今日宜缓").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithText("今日宜缓").assertIsDisplayed()
        compose.onNodeWithTag("recorded_mood").assertTextEquals("轻轻记下了 · 有点累")
        compose.onNodeWithTag("calendar_close").performClick()
        compose.onNodeWithContentDescription("心情日历").performClick()
        compose.onNodeWithText("今日宜缓").assertIsDisplayed()
        compose.onNodeWithTag("change_mood").performClick()
        compose.onNodeWithTag("mood_Happy").performClick()
        compose.waitUntil(10_000) { MoodStore(context).read(date) == Mood.Happy }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeBy(32)
            runCatching { compose.onNodeWithTag("recorded_mood").assertTextEquals("轻轻记下了 · 开心") }.isSuccess
        }
        compose.onNodeWithTag("recorded_mood").assertTextEquals("轻轻记下了 · 开心")
        compose.onNodeWithText("今日宜缓").assertIsDisplayed()
        assertEquals(1, MoodStore(context).week(date).size)
    }

    @Test fun saveFailureStaysOnChoiceAndAllowsRetry() {
        var attempts = 0
        compose.setContent {
            var records by remember { mutableStateOf<Map<LocalDate, Mood>>(emptyMap()) }
            MoodCalendar(date, records, {}, onSave = {
                attempts++
                if (attempts == 1) false else { records = mapOf(date to it); true }
            })
        }
        compose.onNodeWithTag("mood_Tired").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("刚刚没记住，再轻点一次好吗？").assertIsDisplayed()
        compose.onNodeWithTag("daily_fortune").assertDoesNotExist()
        compose.onNodeWithTag("mood_Tired").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("daily_fortune").assertIsDisplayed()
        assertEquals(2, attempts)
    }

    @Test fun recordsArePerDateAndSurviveNewStoreAcrossMonthBoundary() {
        val store = MoodStore(context)
        val lastDay = LocalDate.of(2026, 9, 30)
        assertTrue(store.save(lastDay, Mood.Tired))
        assertNull(MoodStore(context).read(lastDay.plusDays(1)))
        assertEquals(mapOf(lastDay to Mood.Tired), MoodStore(context).week(lastDay.plusDays(1)))
        assertTrue(store.save(lastDay.plusDays(1), Mood.Calm))
        assertTrue(store.save(lastDay, Mood.Happy))
        assertEquals(2, MoodStore(context).week(lastDay.plusDays(1)).size)
        assertEquals(Mood.Happy, MoodStore(context).read(lastDay))
        assertEquals(Mood.Calm, MoodStore(context).read(lastDay.plusDays(1)))
    }
}
