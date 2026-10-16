package com.lookbuddy.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ThreeTabNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlyHomeDiaryAndSettingsRemainAndSelectionRestores() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LookBuddyApp() }
        for (tab in listOf("home", "diary", "settings")) compose.onNodeWithTag("nav_$tab").assertIsDisplayed()
        compose.onNodeWithTag("nav_camera").assertDoesNotExist()
        compose.onNodeWithContentDescription("陪伴视角").assertDoesNotExist()
        compose.onNodeWithTag("nav_diary").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_diary").assertIsSelected()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_settings_device").performClick()
        compose.onNodeWithText("查看陪伴视角").assertDoesNotExist()
        compose.onNodeWithText("知道啦").performClick()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("nav_home").assertIsSelected()
    }
}
