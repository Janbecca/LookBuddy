package com.lookbuddy.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.delay

internal val BuddyCream = Color(0xFFFFF3E2)
private val BuddyClay = Color(0xFFB4745C)
private val BuddyInk = Color(0xFF030100)
private val BuddyHint = Color(0xFFAAAAAA)
private val Pill = RoundedCornerShape(40.dp)
internal val OnboardingHandwriting = FontFamily(Font(R.font.fontqu_smile))
internal val OnboardingDeviceFont = FontFamily(Font(R.font.lxgw_wenkai_light))
internal val LocalReplayIntroduction = staticCompositionLocalOf<() -> Unit> { {} }

// Re-show the corrected full flow once to devices that completed an earlier version.
internal const val CurrentOnboardingVersion = 3

internal enum class StartupStep {
    Splash, Greeting, Introduction, Destiny, ConnectionTeaser, Nickname, Phone, DeviceConnection, FormalIntroduction, Home;

    fun next(): StartupStep = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
    fun previous(): StartupStep = entries[(ordinal - 1).coerceAtLeast(Greeting.ordinal)]
}

/** Local prototype login. Every input, including empty text, is accepted. */
@Composable
internal fun LookBuddyStartup(home: @Composable () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("lookbuddy_onboarding", Context.MODE_PRIVATE)
    var registered by remember { mutableStateOf(preferences.getInt("flow_version", 0) >= CurrentOnboardingVersion) }
    var step by rememberSaveable { mutableStateOf(StartupStep.Splash) }
    var nickname by rememberSaveable { mutableStateOf(preferences.getString("nickname", "") ?: "") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var codeRequested by rememberSaveable { mutableStateOf(false) }
    var replaying by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(step, registered) {
        if (step == StartupStep.Splash) {
            delay(2400)
            step = if (registered && !replaying) StartupStep.Home else StartupStep.Greeting
        } else if (step == StartupStep.Home && !registered) {
            // An old saved Activity state must not skip the newly added connection screens.
            step = StartupStep.Greeting
        }
    }
    BackHandler(enabled = replaying && step == StartupStep.Greeting) {
        replaying = false; step = StartupStep.Home
    }
    BackHandler(enabled = step != StartupStep.Splash && step != StartupStep.Home && step != StartupStep.Greeting) {
        if (keyboardVisible) { keyboard?.hide(); focus.clearFocus() }
        else { focus.clearFocus(); step = step.previous() }
    }
    val advance = {
        keyboard?.hide()
        focus.clearFocus()
        if (step == StartupStep.FormalIntroduction) {
            preferences.edit().putBoolean("completed", true).putInt("flow_version", CurrentOnboardingVersion)
                .putString("nickname", nickname).apply()
            registered = true
            replaying = false
        }
        step = step.next()
    }

    CompositionLocalProvider(LocalReplayIntroduction provides {
        keyboard?.hide(); focus.clearFocus()
        nickname = preferences.getString("nickname", "") ?: ""
        phone = ""; code = ""; codeRequested = false
        replaying = true; step = StartupStep.Splash
    }) {
    Box(Modifier.fillMaxSize().background(if (step == StartupStep.Home) Color.White else BuddyCream)) {
        Crossfade(targetState = step, animationSpec = tween(280), label = "startup page") { visibleStep ->
            if (visibleStep == StartupStep.Home) {
                home()
            } else {
                StartupPage(visibleStep, nickname, phone, code, codeRequested,
                    onNickname = { nickname = it }, onPhone = { phone = it }, onCode = { code = it },
                    onRequestCode = { codeRequested = true },
                    onNext = { if (visibleStep == step) advance() })
            }
        }
        if (replaying) TextButton(onClick = {
            keyboard?.hide(); focus.clearFocus(); replaying = false; step = StartupStep.Home
        }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().testTag("exit_introduction")) {
            Text("返回首页", color = BuddyClay, fontSize = 14.sp)
        }
    }
    }
}

/** Figma 393 × 852. Native Android system bars occupy the iPhone chrome slots.
 * Content is laid out in columns; keyboard and small screens scroll without shrinking fields.
 */
@Composable
internal fun StartupPage(
    step: StartupStep,
    nickname: String = "",
    phone: String = "",
    code: String = "",
    codeRequested: Boolean = false,
    onNickname: (String) -> Unit = {},
    onPhone: (String) -> Unit = {},
    onCode: (String) -> Unit = {},
    onRequestCode: () -> Unit = {},
    onNext: () -> Unit = {},
    animateSplash: Boolean = true
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val dismissKeyboard = { keyboard?.hide(); focus.clearFocus() }
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = OnboardingHandwriting)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(BuddyCream).systemBarsPadding().imePadding()) {
        val scale = (maxWidth.value / 393f).coerceAtMost(1.35f)
        val density = LocalDensity.current
        val contentHeight = maxOf(maxHeight.value, 756f * scale).dp
        // Match the artwork frame even when Android's status bar is shorter than iOS's.
        val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp().value }
        val designTop = (62f * scale - statusTop).coerceAtLeast(0f).dp
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = contentHeight)
            .semantics { contentDescription = step.name }.testTag("page_${step.name}"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (step == StartupStep.Splash) {
                Spacer(Modifier.height(designTop + (300f - 62f).dp * scale))
                if (animateSplash) SplashBuddy(scale) else BuddyImage(scale, step)
                Spacer(Modifier.height(10.dp * scale))
                Text("I am LookBuddy\n我来陪你了！", Modifier.fillMaxWidth(), color = BuddyInk,
                    fontSize = (24f * scale).sp, lineHeight = (26f * scale).sp, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
            } else if (step == StartupStep.Phone) {
                Spacer(Modifier.height(designTop + 45.dp * scale))
                StoryText("啊偶\n还需要登陆一下捏~", scale, Modifier.height(78.dp * scale))
                BuddyImage(scale, step)
                Spacer(Modifier.height(18.dp * scale))
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp * scale),
                    verticalArrangement = Arrangement.spacedBy(21.dp * scale)) {
                    BuddyInput(phone, onPhone, "输入手机号", "phone", scale,
                        imeAction = ImeAction.Next)
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp * scale),
                        horizontalArrangement = Arrangement.spacedBy(1.dp * scale)) {
                        BuddyInput(code, onCode, "输入验证码", "code", scale,
                            Modifier.weight(1f), imeAction = ImeAction.Done, onDone = dismissKeyboard)
                        BuddyButton(if (codeRequested) "已获取" else "获取\n验证码", scale,
                            Modifier.width(91.dp * scale), fontSize = 15f, tag = "request_code", onClick = onRequestCode)
                    }
                    BuddyButton("登陆", scale, Modifier.fillMaxWidth().offset(x = 4.dp * scale),
                        tag = "next", onClick = onNext)
                    if (codeRequested) Text("演示模式：任意验证码均可继续", color = BuddyClay,
                        fontSize = (13f * scale).sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("code_feedback"))
                }
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.height(designTop))
                val top = when (step) {
                    StartupStep.Greeting -> 144f
                    StartupStep.Introduction -> 102f
                    StartupStep.Destiny -> 63f
                    StartupStep.DeviceConnection -> 95f
                    StartupStep.FormalIntroduction -> 63f
                    else -> 155f
                }
                val lines = when (step) {
                    StartupStep.Greeting -> 3
                    StartupStep.Introduction -> 4
                    StartupStep.Destiny -> 5
                    StartupStep.DeviceConnection -> 4
                    StartupStep.FormalIntroduction -> 5
                    else -> 2
                }
                // Story and character use their Figma slots; controls are regular column/row layouts.
                Box(Modifier.fillMaxWidth().height(258.dp * scale)) {
                    StoryText(story(step), scale,
                        Modifier.padding(top = top.dp * scale).height((lines * 39).dp * scale))
                }
                BuddyImage(scale, step)
                Spacer(Modifier.height(when (step) {
                    StartupStep.DeviceConnection -> 25.dp
                    StartupStep.FormalIntroduction -> 12.dp
                    else -> 34.dp
                } * scale))
                val replyOffset = if (step == StartupStep.Destiny || step == StartupStep.ConnectionTeaser) 5f else 0f
                if (step == StartupStep.DeviceConnection) {
                    DeviceConnectionList(scale, onNext)
                } else Column(Modifier.fillMaxWidth().offset(x = replyOffset.dp * scale).padding(horizontal = 20.dp * scale),
                    verticalArrangement = Arrangement.spacedBy(25.dp * scale)) {
                    if (step == StartupStep.Nickname) {
                        BuddyInput(nickname, onNickname, "输入你的昵称", "nickname", scale,
                            imeAction = ImeAction.Done, onDone = dismissKeyboard)
                        BuddyButton("我们继续", scale, tag = "next", onClick = onNext)
                    } else if (step == StartupStep.FormalIntroduction) {
                        BuddyButton("嗯嗯好的", scale, tag = "next", onClick = onNext)
                    } else {
                        replies(step).forEachIndexed { index, reply ->
                            BuddyButton(reply, scale, tag = "reply_$index", onClick = onNext)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

private fun story(step: StartupStep): String = when (step) {
    StartupStep.Greeting -> "Hey！Buddy！\n你来了！？\n其实我等你好久了"
    StartupStep.Introduction -> "我平常生活在异星球\n最近\n逛地球的时候\n觉得你还挺有趣的"
    StartupStep.Destiny -> "更巧的是\n你还买了我们星球和安克\n秘密合作的通讯设备\n看来我们的缘分\n真的不简单"
    StartupStep.ConnectionTeaser -> "那让我们建立\n更加稳固的链接吧！"
    StartupStep.Nickname -> "对了，我插一句\n你叫什么？"
    StartupStep.DeviceConnection -> "正式创立连接吧~\n1、打开你的蓝牙\n2、选你的专属设备\n3、链接成功就好了"
    StartupStep.FormalIntroduction -> "OK你真棒\n你之后就通过它来找我吧\n正式自我介绍一下\n我叫LookBuddy\n我很会照顾打工人哦~"
    else -> ""
}

private fun replies(step: StartupStep): List<String> = when (step) {
    StartupStep.Greeting -> listOf("你好，很高兴认识你", "哦？是吗？但你是谁啊")
    StartupStep.Introduction -> listOf("谢谢", "你也很友好啊~")
    StartupStep.Destiny -> listOf("尊嘟假嘟？", "哈哈，那真的很有缘分了")
    StartupStep.ConnectionTeaser -> listOf("好呀好呀", "我准备好了 ！")
    else -> emptyList()
}

@Composable
private fun DeviceConnectionList(scale: Float, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(287.dp * scale)
        .background(Color.White, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
        .padding(horizontal = 16.dp * scale, vertical = 8.dp * scale)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp * scale)) {
        repeat(6) { index ->
            Column {
                Row(Modifier.fillMaxWidth().height(47.dp * scale).padding(end = 8.dp * scale),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("设备${if (index == 1) 2 else 1}：", color = BuddyInk, fontFamily = OnboardingDeviceFont, fontSize = (16f * scale).sp,
                        modifier = Modifier.width(73.dp * scale))
                    Text("jcaohpuhhaffoe", color = BuddyInk, fontFamily = OnboardingDeviceFont, fontSize = (14f * scale).sp,
                        modifier = Modifier.weight(1f))
                    Box(Modifier.width(62.dp * scale).height(36.dp * scale)
                        .background(if (index == 1) Color(0xFFDFF9F2) else Color(0xFFF4F4F4), Pill)
                        .clip(Pill).clickable(role = Role.Button, onClick = onConnect)
                        .testTag("connect_$index"), contentAlignment = Alignment.Center) {
                        Text(if (index == 1) "已连接" else "点击连接", color = BuddyInk, fontFamily = OnboardingDeviceFont, fontSize = (10f * scale).sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF4F4F4)))
            }
        }
    }
}

@Composable
private fun StoryText(value: String, scale: Float, modifier: Modifier = Modifier) {
    val annotated = buildAnnotatedString {
        val highlighted = value.indexOf("安克")
        if (highlighted < 0) append(value) else {
            append(value.substring(0, highlighted))
            withStyle(SpanStyle(color = BuddyClay)) { append("安克") }
            append(value.substring(highlighted + 2))
        }
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp * scale), contentAlignment = Alignment.Center) {
        Text(annotated, color = BuddyInk, fontSize = (36f * scale).sp,
            lineHeight = (39f * scale).sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BuddyImage(scale: Float, step: StartupStep = StartupStep.Greeting, modifier: Modifier = Modifier) {
    val width = when (step) {
        StartupStep.Phone -> 281f
        StartupStep.FormalIntroduction -> 500f
        StartupStep.DeviceConnection -> 313f
        StartupStep.Splash -> 332f
        else -> 332.5f
    }
    val height = when (step) {
        StartupStep.Phone -> 161f
        StartupStep.FormalIntroduction -> 286f
        StartupStep.DeviceConnection -> 179f
        else -> 190f
    }
    Box(modifier.fillMaxWidth().height((if (step == StartupStep.DeviceConnection) 186f else height).dp * scale)) {
        OnboardingArtwork(Modifier.align(Alignment.TopCenter)
            .offset(x = (if (step == StartupStep.FormalIntroduction) -11.25f else 0f).dp * scale,
                y = (if (step == StartupStep.DeviceConnection) -3f else 0f).dp * scale)
            .requiredSize(width.dp * scale, height.dp * scale))
    }
}

/** Single replacement point for future animated IP. Every startup page uses a verified single-frame PNG placeholder. */
@Composable
internal fun OnboardingArtwork(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.onboarding_buddy_placeholder), "LookBuddy 戴着棕色帽子的小伙伴",
        modifier, contentScale = ContentScale.Crop)
}

@Composable
private fun SplashBuddy(scale: Float) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(850)) }
    BuddyImage(scale, StartupStep.Splash, Modifier.graphicsLayer {
        alpha = entrance.value
        scaleX = 0.9f + 0.1f * entrance.value
        scaleY = scaleX
        translationY = 14f * (1f - entrance.value) * scale * density
    })
}

@Composable
private fun BuddyButton(
    label: String, scale: Float, modifier: Modifier = Modifier,
    fontSize: Float = 32f, tag: String, onClick: () -> Unit
) {
    Box(modifier.then(if (modifier == Modifier) Modifier.fillMaxWidth() else Modifier)
        .height(49.dp * scale).shadow(4.dp, Pill).background(BuddyClay, Pill)
        .clip(Pill).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontSize = (fontSize * scale).sp,
            lineHeight = ((fontSize + 2) * scale).sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BuddyInput(
    value: String, onValueChange: (String) -> Unit, hint: String, tag: String, scale: Float,
    modifier: Modifier = Modifier, imeAction: ImeAction = ImeAction.Done, onDone: () -> Unit = {}
) {
    BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
        modifier = modifier.then(if (modifier == Modifier) Modifier.fillMaxWidth() else Modifier)
            .height(49.dp * scale).clip(Pill).background(Color(0x75F4F4F4))
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                    startY = 0f, endY = 10.dp.toPx()))
            }.testTag(tag).semantics { contentDescription = hint },
        textStyle = TextStyle(color = BuddyInk, fontFamily = OnboardingHandwriting, fontSize = (32f * scale).sp, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(BuddyClay),
        // Text keyboard intentionally permits letters, punctuation and arbitrary input for this prototype.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 15.dp * scale), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) Text(hint, color = BuddyHint, fontSize = (32f * scale).sp,
                    textAlign = TextAlign.Center)
                inner()
            }
        })
}

@Preview(name = "01 开屏动画页", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun SplashPreview() = StartupPage(StartupStep.Splash)
@Preview(name = "02 打招呼", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun GreetingPreview() = StartupPage(StartupStep.Greeting)
@Preview(name = "03 LookBuddy 自我介绍", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun IntroductionPreview() = StartupPage(StartupStep.Introduction)
@Preview(name = "04 缘分不简单", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun DestinyPreview() = StartupPage(StartupStep.Destiny)
@Preview(name = "05 链接设备预告", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun ConnectionPreview() = StartupPage(StartupStep.ConnectionTeaser)
@Preview(name = "06 buddy 询问", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun NicknamePreview() = StartupPage(StartupStep.Nickname)
@Preview(name = "07 手机验证", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun PhonePreview() = StartupPage(StartupStep.Phone)
@Preview(name = "08 设备连接", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun DeviceConnectionPreview() = StartupPage(StartupStep.DeviceConnection)
@Preview(name = "09 正式介绍", widthDp = 393, heightDp = 852, showBackground = true)
@Composable private fun FormalIntroductionPreview() = StartupPage(StartupStep.FormalIntroduction)
