package com.lookbuddy.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import java.time.LocalDateTime

@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun DrinkReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Drink)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun FatigueReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Fatigue)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun PostureReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Posture)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun StandReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Stand)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun HappyReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Happy)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun WorriedReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Worried)
@PreviewTest @Preview(widthDp=320, heightDp=640, fontScale=1.3f, showSystemUi=true, device="spec:width=320dp,height=640dp,dpi=160")
@Composable fun CompactDrinkReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Drink)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun NightDrinkReminderScreenshot() = ReminderScreenshot(HomeReminderKind.Drink, night=true)

@Composable private fun ReminderScreenshot(kind: HomeReminderKind, night: Boolean=false) {
    val state = remember { HomeReminderController().apply { preview(kind); finish(active!!.id) } }
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib, LocalBuddyMotionEnabled provides false) {
        MorningHomeScreen(previewTime=LocalDateTime.of(2026,10,16,if(night)20 else 12,30),
            previewDate=java.time.LocalDate.of(2026,10,16), reminders=state)
    }
}

@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun MorningGreetingScreenshot() = ReminderScreenshot(HomeReminderKind.MorningGreeting)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun EveningGreetingScreenshot() = ReminderScreenshot(HomeReminderKind.EveningGreeting, night=true)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun DiaryReadyReminderScreenshot() = ReminderScreenshot(HomeReminderKind.DiaryReady)
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun DefaultDayArtworkScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib, LocalBuddyMotionEnabled provides false) {
        MorningHomeScreen(previewTime=LocalDateTime.of(2026,10,16,12,30), previewDate=java.time.LocalDate.of(2026,10,16))
    }
}
@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun DefaultNightArtworkScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib, LocalBuddyMotionEnabled provides false) {
        MorningHomeScreen(previewTime=LocalDateTime.of(2026,10,16,20,30), previewDate=java.time.LocalDate.of(2026,10,16))
    }
}
