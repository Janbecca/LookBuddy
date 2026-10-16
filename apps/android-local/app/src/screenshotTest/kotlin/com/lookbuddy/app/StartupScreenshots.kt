package com.lookbuddy.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import android.content.res.AssetManager
import androidx.compose.runtime.CompositionLocalProvider
import com.caverock.androidsvg.SVG
import java.io.InputStream

// Render the actual Android composables for visual review against the Figma screens.
// Splash is animated in production; use the static artwork for a deterministic snapshot.
@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun SplashScreenshot() = StartupPage(StartupStep.Splash, animateSplash = false)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun GreetingScreenshot() = StartupPage(StartupStep.Greeting)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun IntroductionScreenshot() = StartupPage(StartupStep.Introduction)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun DestinyScreenshot() = StartupPage(StartupStep.Destiny)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun ConnectionScreenshot() = StartupPage(StartupStep.ConnectionTeaser)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun NicknameScreenshot() = StartupPage(StartupStep.Nickname)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun PhoneScreenshot() = StartupPage(StartupStep.Phone)

@PreviewTest
@Preview(widthDp = 320, heightDp = 640, fontScale = 1.15f, showSystemUi = true)
@Composable
fun CompactPhoneScreenshot() = StartupPage(StartupStep.Phone, phone = "anything", code = "随意", codeRequested = true)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun DeviceConnectionScreenshot() = StartupPage(StartupStep.DeviceConnection)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun FormalIntroductionScreenshot() = StartupPage(StartupStep.FormalIntroduction)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable
fun MorningHomeScreenshot() = ScreenshotHome(false)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable
fun ExpandedStatisticsScreenshot() = ScreenshotHome(true)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun EnteredNicknameScreenshot() = StartupPage(StartupStep.Nickname, nickname = "小禾")

// Layoutlib's XmlPullParser lacks the DOCDECL feature used by AndroidSVG 1.4.
// Use the library's SAX parser only in screenshot tests; device builds use its public API.
internal fun loadSvgForLayoutlib(assets: AssetManager, filename: String): SVG {
    val parserClass = Class.forName("com.caverock.androidsvg.SVGParser")
    val parser = parserClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    val method = parserClass.getDeclaredMethod("parseUsingSAX", InputStream::class.java)
        .apply { isAccessible = true }
    assets.open(filename).use { method.invoke(parser, it) }
    return parserClass.getDeclaredField("svgDocument").apply { isAccessible = true }.get(parser) as SVG
}

@Composable
private fun ScreenshotHome(expanded: Boolean) {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        MorningHomeScreen(initialExpanded = expanded, previewDate = java.time.LocalDate.of(2026, 10, 16))
    }
}

private val calendarPreviewDate = java.time.LocalDate.of(2026, 10, 13)
private fun calendarPreviewRecords() = listOf(Mood.Happy, Mood.Calm, Mood.Sad, Mood.Happy, Mood.Happy, Mood.Calm)
    .mapIndexed { index, mood -> calendarPreviewDate.minusDays(6L - index) to mood }.toMap()

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable
fun MoodSelectionScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        MorningHomeScreen(initialCalendarOpen = true, previewDate = calendarPreviewDate,
            previewRecords = calendarPreviewRecords())
    }
}

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable
fun MoodFortuneScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        MorningHomeScreen(initialCalendarOpen = true, previewDate = calendarPreviewDate,
            previewRecords = calendarPreviewRecords() + (calendarPreviewDate to Mood.Tired))
    }
}

@PreviewTest
@Preview(widthDp = 320, heightDp = 640, fontScale = 1.3f, showSystemUi = true, device="spec:width=320dp,height=640dp,dpi=160")
@Composable
fun CompactMoodSelectionScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        MorningHomeScreen(initialCalendarOpen = true, previewDate = calendarPreviewDate,
            previewRecords = emptyMap())
    }
}

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable
fun EveningHomeScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        MorningHomeScreen(previewDate = java.time.LocalDate.of(2026, 10, 16),
            previewTime = java.time.LocalDateTime.of(2026, 10, 16, 19, 0))
    }
}

@PreviewTest
@Preview(widthDp = 320, heightDp = 640, fontScale = 1.5f, showSystemUi = true, device="spec:width=320dp,height=640dp,dpi=160")
@Composable
fun CompactMoodFortuneScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        MorningHomeScreen(initialCalendarOpen = true, previewDate = calendarPreviewDate,
            previewRecords = calendarPreviewRecords() + (calendarPreviewDate to Mood.Tired))
    }
}
