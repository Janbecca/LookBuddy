package com.lookbuddy.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FourTabNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatKeepsDraftAndUnsentMessagesAcrossRestorationAndReturnsToDiary() {
        var back: OnBackPressedDispatcher? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LookBuddyApp(initialPage = CompanionPage.Diary)
        }
        compose.onNodeWithTag("diary_chat_entry").performClick()
        compose.onNodeWithTag("buddy_chat_screen").assertExists()
        compose.onNodeWithTag("chat_connection_hint").assertTextEquals("聊天还未连接，内容暂留在本页")
        compose.onNodeWithTag("chat_input").performTextInput("今天有件小事")
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithText("暂未发送").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").performTextInput("还没写完")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chat_input").assertTextContains("还没写完")
        compose.onNodeWithText("今天有件小事").assertExists()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_diary").assertIsSelected()
        compose.onNodeWithTag("diary_chat_entry").performClick()
        compose.onNodeWithTag("chat_input").assertTextContains("还没写完")
        compose.onNodeWithTag("nav_camera").performClick()
        compose.onNodeWithTag("camera_screen").assertExists()
        compose.onNodeWithTag("nav_camera").assertIsSelected()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("morning_home").assertExists()
    }

    @Test fun injectedRemindersOpenChatWithTheirMessageAndDiaryReadyOpensDiary() {
        val events = MutableStateFlow(HomeReminderEvent("test:fatigue", HomeReminderKind.Fatigue,
            message = "歇一会儿，和我聊聊吧"))
        val acknowledgements = mutableListOf<String>()
        val source = object : HomeReminderSource {
            override val reminders = events
            override suspend fun acknowledge(id: String) { acknowledgements.add(id) }
        }
        compose.setContent { LookBuddyApp(reminderSource = source) }
        compose.onNodeWithTag("reminder_message").assertTextEquals("歇一会儿，和我聊聊吧")
        compose.onNodeWithTag("acknowledge_reminder").performClick()
        compose.onNodeWithTag("buddy_chat_screen").assertExists()
        compose.onNodeWithText("歇一会儿，和我聊聊吧").assertIsDisplayed()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
        compose.runOnIdle { events.value = HomeReminderEvent("test:diary", HomeReminderKind.DiaryReady) }
        compose.onNodeWithTag("acknowledge_reminder").performClick()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_diary").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf("test:fatigue", "test:diary"), acknowledgements) }
    }

    @Test fun allFourTabsKeepTheirOwnDestinationAndSelectionAfterRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LookBuddyApp() }
        val tabs = listOf("home", "camera", "diary", "settings")
        for (tab in tabs) compose.onNodeWithTag("nav_" + tab).assertIsDisplayed()
        compose.onNodeWithTag("nav_home").assertIsSelected()
        compose.onNodeWithTag("nav_camera").performClick()
        compose.onNodeWithTag("camera_screen").assertExists()
        compose.onNodeWithTag("nav_camera").assertIsSelected()
        compose.onNodeWithTag("nav_home").assertIsNotSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("camera_screen").assertExists()
        compose.onNodeWithTag("nav_camera").assertIsSelected()
        compose.onNodeWithTag("nav_diary").performClick()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_diary").assertIsSelected()
        compose.onNodeWithTag("nav_camera").assertIsNotSelected()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.onNodeWithTag("nav_diary").performClick()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("morning_home").assertExists()
        compose.onNodeWithTag("nav_home").assertIsSelected()
    }
}
