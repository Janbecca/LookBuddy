package com.lookbuddy.app

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionPagesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsKeepHomeDrawerAndSelectedPageAfterRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LookBuddyApp() }
        compose.onNodeWithTag("stats_toggle").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("nav_diary").performClick()
        compose.onNodeWithTag("diary_screen").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_settings_device").performClick()
        compose.onNodeWithText("知道啦").performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
    }

    @Test fun diaryScrollsToLastEntryAndOpensHonestPreview() {
        compose.setContent { LookBuddyApp(CompanionPage.Diary) }
        compose.onNodeWithText("看看示例日记").performScrollTo().performClick()
        compose.onNodeWithTag("diary_bubbles").performTouchInput { swipeUp() }
        compose.onNodeWithTag("diary_entry_7").performScrollTo().performClick()
        compose.onNodeWithText("18:05 · 挥手下班").assertIsDisplayed()
        compose.onNodeWithText("这是设计稿中的示例关键帧，尚未接入真实视频。").assertIsDisplayed()
        compose.onNodeWithText("知道啦").performClick()
        compose.onNodeWithTag("nav_settings").assertIsDisplayed()
    }

    @Test fun profileCancelDoesNotSaveButSavePersists() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lookbuddy_onboarding", Context.MODE_PRIVATE)
        prefs.edit().putString("nickname", "原昵称").commit()
        compose.setContent { LookBuddyApp(CompanionPage.Settings) }
        compose.onNodeWithTag("settings_settings_profile").performClick()
        compose.onNodeWithTag("profile_nickname").performTextReplacement("取消的昵称")
        compose.onNodeWithText("取消").performClick()
        assertEquals("原昵称", prefs.getString("nickname", null))
        compose.onNodeWithTag("settings_settings_profile").performClick()
        compose.onNodeWithTag("profile_nickname").assertTextContains("原昵称")
        compose.onNodeWithTag("profile_nickname").performTextReplacement("新的搭子")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) {
            prefs.getString("nickname", null) == "新的搭子"
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings_settings_profile").performClick()
        compose.onNodeWithTag("profile_nickname").assertTextContains("新的搭子")
        compose.onNodeWithText("取消").performClick()
    }

}
