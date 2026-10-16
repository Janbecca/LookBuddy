package com.lookbuddy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalSettingsScreen(s: Float, onCamera: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("lookbuddy_onboarding", Context.MODE_PRIVATE) }
    var nickname by remember { mutableStateOf(preferences.getString("nickname", "") ?: "") }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(nickname) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val schedule = rememberWorkSchedule()
    val scheduleStore = remember(context) { WorkScheduleStore(context) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    fun pickWorkTime(start: Boolean) {
        val time = if (start) schedule.start else schedule.end
        android.app.TimePickerDialog(context, { _, hour, minute ->
            val selected = java.time.LocalTime.of(hour, minute)
            val nextStart = if (start) selected else schedule.start
            val nextEnd = if (start) schedule.end else selected
            if (nextStart == nextEnd) scheduleError = "上班与下班时间不能相同。"
            else { scheduleStore.save(WorkSchedule(nextStart, nextEnd)); scheduleError = null }
        }, time.hour, time.minute, true).show()
    }
    val replayIntroduction = LocalReplayIntroduction.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun open(name: String) { error = null; editing = nickname; dialog = name }
    val saveProfile: () -> Unit = {
        if (!saving) {
            saving = true
            keyboard?.hide(); focus.clearFocus()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { preferences.edit().putString("nickname", editing).commit() }.getOrDefault(false)
                }
                saving = false
                if (ok) { nickname = editing; dialog = null } else error = "保存失败，请重试。"
            }
        }
    }
    if (dialog == "个人资料") {
        BackHandler { if (!saving) dialog = null }
        Column(Modifier.fillMaxSize().testTag("profile_editor").imePadding().verticalScroll(rememberScrollState())
            .padding(20.dp * s), verticalArrangement = Arrangement.spacedBy(20.dp * s)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !saving, onClick = { keyboard?.hide(); focus.clearFocus(); dialog = null }) {
                    Text("取消", color = CompanionMuted)
                }
                Text("个人资料", color = CompanionInk, fontSize = (20 * s).sp, modifier = Modifier.weight(1f))
                TextButton(enabled = !saving, onClick = saveProfile) {
                    Text(if (saving) "保存中…" else "保存", color = CompanionClay)
                }
            }
            Text("昵称", color = CompanionMuted, fontSize = 14.sp)
            BasicTextField(value = editing, onValueChange = { editing = it },
                textStyle = TextStyle(color = CompanionInk, fontSize = 18.sp),
                cursorBrush = SolidColor(CompanionClay), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, CompanionBorder, RoundedCornerShape(12.dp)).padding(14.dp).testTag("profile_nickname"))
            Text("昵称保存在这台设备上。头像暂用设计稿示例。", color = CompanionMuted, fontSize = 12.sp)
            error?.let { Text(it, color = CompanionClay) }
        }
        return
    }
    Column(Modifier.fillMaxSize().testTag("settings_screen").verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp * s).padding(top = 16.dp * s, bottom = 30.dp * s)) {
        Text("个人设置", color = CompanionInk, fontSize = (22 * s).sp)
        Spacer(Modifier.height(20.dp * s))
        Image(painterResource(R.drawable.profile_avatar), "示例头像，点击编辑个人资料",
            Modifier.align(Alignment.CenterHorizontally).size(101.dp * s).clip(CircleShape)
                .border(2.dp, CompanionBorder, CircleShape).clickable(role = Role.Button) { open("个人资料") }, contentScale = ContentScale.Crop)
        Spacer(Modifier.height(17.dp * s))
        Text("今天，也要照顾好自己", color = CompanionMuted, fontSize = (14 * s).sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(32.dp * s))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp * s)) {
            listOf("个人资料" to "settings_profile", "账号安全" to "settings_security",
                "系统设置" to "settings_system", "设备连接" to "settings_device").forEach { (label, icon) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp * s).clip(RoundedCornerShape(16.dp * s))
                    .background(Color.White).border(.6.dp, CompanionBorder, RoundedCornerShape(16.dp * s))
                    .clickable(role = Role.Button) { open(label) }.testTag("settings_$icon")
                    .padding(16.dp * s), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp * s)) {
                    Box(Modifier.size(32.dp * s), contentAlignment = Alignment.Center) {
                        FigmaSvg(icon, s, folder = "pages")
                    }
                    Text(label, color = CompanionInk, fontSize = (18 * s).sp, modifier = Modifier.weight(1f))
                    Text("›", color = CompanionMuted, fontSize = (24 * s).sp)
                }
            }
        }
        Spacer(Modifier.height(26.dp * s))
        Text("把日常的小事，交给 Buddy 陪你记着。", color = CompanionMuted, fontSize = (12 * s).sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    dialog?.let { selected ->
        AlertDialog(onDismissRequest = { if (!saving) dialog = null }, containerColor = CompanionPaper,
            title = { Text(selected, color = CompanionInk) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (selected) {
                    "账号安全" -> Text("当前为本地体验，尚未接入真实账号登录。昵称和心情记录保存在这台设备上。", color = CompanionMuted)
                    "系统设置" -> Text("可在 Android 应用设置中管理 LookBuddy 的权限、通知和存储。", color = CompanionMuted)
                    "设备连接" -> Text("在陪伴视角查看摄像头连接状态、原视频和记录高光时刻。", color = CompanionMuted)
                }
                if (selected == "系统设置") {
                    Text("工作时间（每日）", color = CompanionInk)
                    TextButton(onClick = { pickWorkTime(true) }, modifier = Modifier.testTag("work_start_time")) {
                        Text("上班时间  ${schedule.start}", color = CompanionClay)
                    }
                    TextButton(onClick = { pickWorkTime(false) }, modifier = Modifier.testTag("work_end_time")) {
                        Text("下班时间  ${schedule.end}", color = CompanionClay)
                    }
                    Text("到点自动切换上下班；18:00 至次日 06:00 使用晚间背景。", color = CompanionMuted, fontSize = 12.sp)
                    scheduleError?.let { Text(it, color = CompanionClay) }
                }
                if (selected == "系统设置") TextButton(onClick = {
                    dialog = null
                    replayIntroduction()
                }, modifier = Modifier.testTag("replay_introduction")) {
                    Text("重新体验初次见面", color = CompanionClay)
                }
                error?.let { Text(it, color = CompanionClay) }
            } },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    when (selected) {
                        "设备连接" -> { dialog = null; onCamera() }
                        "系统设置" -> runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }.onSuccess { dialog = null }.onFailure { error = "无法打开系统设置。" }.let { }
                        else -> dialog = null
                    }
                }) { Text(when (selected) {
                    "设备连接" -> "查看陪伴视角"
                    "系统设置" -> "打开应用设置"
                    else -> "知道啦"
                }, color = CompanionClay) }
            }, dismissButton = {
                if (selected != "账号安全") TextButton(enabled = !saving, onClick = { dialog = null }) { Text("取消", color = CompanionMuted) }
            })
    }
}
