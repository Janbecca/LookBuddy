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
class FourTabNavigationTest {
    @get:Rule val compose = createComposeRule()
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
