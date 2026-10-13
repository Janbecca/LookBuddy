package com.lookbuddy.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lookbuddy.app.data.DemoReplay
import com.lookbuddy.app.sdk.EufyAccountConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable fun LookBuddyDebug(model: LookBuddyViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        model.foreground(true); try { awaitCancellation() } finally { model.foreground(false) }
    } }
    var backend by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var name by remember { mutableStateOf("体验用户") }
    var path by remember { mutableStateOf("/v1/preferences") }; var method by remember { mutableStateOf("GET") }; var body by remember { mutableStateOf("{}") }
    var scenario by remember { mutableStateOf("recovery") }; var learning by remember { mutableStateOf(false) }; var confirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.user?.id) { if (state.user != null) password = "" }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LookBuddy · Android 调试", style = MaterialTheme.typography.titleLarge)
        EufyPanel(state, model) { confirmation = it }
        HorizontalDivider()
        Text(state.connection)
        OutlinedTextField(backend, { backend = it }, label = { Text("后端 origin；模拟器10.0.2.2，真机局域网IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button({ model.connect(backend) }, enabled = !state.busy) { Text("连接 / 切换（更换地址会退出登录）") }
        if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.user == null) {
            OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("密码，不保存") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("注册显示名") }, singleLine = true)
            Row { Button({ model.authenticate(email, password, null) }, enabled = !state.busy) { Text("登录") }; Button({ model.authenticate(email, password, name) }, enabled = !state.busy) { Text("注册") } }
        } else {
            Text("${state.user!!.displayName} · ${state.user!!.email}")
            Row { Button({ model.refresh() }, enabled = !state.busy) { Text("补查 / 重连") }; Button({ model.logout() }, enabled = !state.busy) { Text("退出登录") } }
            state.preferences?.let { prefs ->
                Text("久坐阈值：${prefs.sedentaryMinutes} 分钟")
                Row { listOf(30, 60, 90).forEach { n -> TextButton({ model.preferences(buildJsonObject { put("sedentaryMinutes", n) }) }, enabled = !state.busy) { Text("$n") } } }
                Text("起身目标：${prefs.breakMinutes} 分钟")
                Row { listOf(5, 10, 15, 20).forEach { n -> TextButton({ model.preferences(buildJsonObject { put("breakMinutes", n) }) }, enabled = !state.busy) { Text("$n") } } }
                Toggle("忙碌状态", prefs.busy, !state.busy) { model.preferences(buildJsonObject { put("busy", it) }) }
                Toggle("姿态提醒", prefs.postureEnabled, !state.busy) { model.preferences(buildJsonObject { put("postureEnabled", it) }) }
                Toggle("情绪陪伴", prefs.companionEnabled, !state.busy) { model.preferences(buildJsonObject { put("companionEnabled", it) }) }
            }
            Text("待处理提醒：${state.reminders.size}")
            state.reminders.forEach { reminder -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${reminder.category} · ${reminder.id}")
                if (reminder.id !in state.displayed) Button({ model.display(reminder.id) }, enabled = !state.busy) { Text("显示并登记提醒") }
                else { Text(reminder.message); Row { listOf("accepted" to "接受", "snoozed" to "稍后10分", "ignored" to "忽略").forEach { (action, label) -> TextButton({ model.respond(reminder.id, action) }, enabled = !state.busy) { Text(label) } } } }
            } } }
            Text("记录来源：${state.namespace}（demo / live 分开）")
            Row { listOf("live", "demo").forEach { value -> TextButton({ model.namespace(value) }) { Text(value) } } }
            Text("干预闭环\n${state.interventions}"); Text("表情假设，不是诊断\n${state.expressions}"); Text("时间线\n${state.timeline}")
            Row { TextButton({ model.inspect("/v1/learning/states?namespace=${state.namespace}") }, enabled = !state.busy) { Text("学习参数") }; TextButton({ model.inspect("/v1/learning/rewards?namespace=${state.namespace}") }, enabled = !state.busy) { Text("奖励账本") } }
            Row { TextButton({ model.inspect("/v1/learning/config") }, enabled = !state.busy) { Text("学习规则") }; TextButton({ model.inspect("/v1/scheduler/jobs") }, enabled = !state.busy) { Text("调度记录") } }
            Text("模拟加速回放只写 demo，不代表真人效果")
            Choice("场景", scenario, DemoReplay.scenarios, !state.busy) { scenario = it }; Toggle("更新 demo 学习", learning, !state.busy) { learning = it }
            Button({ model.replay(scenario, learning) }, enabled = !state.busy) { Text("运行模拟闭环") }; TextButton({ confirmation = "reset" }, enabled = !state.busy) { Text("重置 demo") }
            Text("API 控制台：账号操作使用专用按钮")
            OutlinedTextField(path, { path = it }, label = { Text("/v1/ 路径") }, modifier = Modifier.fillMaxWidth())
            Choice("方法", method, listOf("GET", "POST", "PATCH", "DELETE"), !state.busy) { method = it }
            OutlinedTextField(body, { body = it }, label = { Text("JSON 请求体") }, modifier = Modifier.fillMaxWidth())
            Button({ if (method == "DELETE") confirmation = "delete-api" else model.execute(path, method, body) }, enabled = !state.busy) { Text("执行") }
            Text(state.result); Button({ model.generateDiary() }, enabled = !state.busy) { Text("生成今日记录") }; Text(state.diary)
            TextButton({ confirmation = "account" }, enabled = !state.busy) { Text("删除账号及全部数据") }
        }
    }
    confirmation?.let { kind ->
        val privacy = kind.startsWith("privacy-")
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (privacy) "确认设备控制" else "确认删除") },
            text = {
                Text(
                    when (kind) {
                        "account" -> "永久删除账号及关联数据？"
                        "reset" -> "仅清理 demo 数据？"
                        "privacy-on" -> "确认开启所选设备的隐私模式？命令会发送到真实设备。"
                        "privacy-off" -> "确认关闭所选设备的隐私模式并恢复摄像？命令会发送到真实设备。"
                        else -> "DELETE $path？"
                    }
                )
            },
            confirmButton = {
                TextButton({
                    confirmation = null
                    when (kind) {
                        "account" -> model.deleteAccount()
                        "reset" -> model.execute("/v1/debug/learning/reset", "POST", "{}")
                        "privacy-on" -> model.setEufyPrivacyMode(true)
                        "privacy-off" -> model.setEufyPrivacyMode(false)
                        else -> model.execute(path, method, body)
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton({ confirmation = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EufyPanel(state: ClientState, model: LookBuddyViewModel, confirm: (String) -> Unit) {
    var appName by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var authorization by remember { mutableStateOf("") }
    var appDomain by remember { mutableStateOf("") }
    var deviceDomain by remember { mutableStateOf("") }
    var presetKey by remember { mutableStateOf("US") }
    LaunchedEffect(state.eufyReady) { if (state.eufyReady) authorization = "" }

    Text("eufy 官方 App SDK 2.2.7", style = MaterialTheme.typography.titleMedium)
    Text(state.eufyStatus)
    if (!state.eufyReady) {
        Text("凭证只保留在当前进程内，不写入 SharedPreferences、日志或后端。")
        OutlinedTextField(appName, { appName = it }, label = { Text("appName") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(clientId, { clientId = it }, label = { Text("clientId") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(userId, { userId = it }, label = { Text("eufy userId") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            authorization,
            { authorization = it },
            label = { Text("authorization / token（不保存）") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(appDomain, { appDomain = it }, label = { Text("App Domain（HTTPS）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(deviceDomain, { deviceDomain = it }, label = { Text("Device Domain（HTTPS）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(presetKey, { presetKey = it }, label = { Text("presetKey") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(
            {
                model.initializeEufy(
                    EufyAccountConfig(
                        appName = appName,
                        clientId = clientId,
                        userId = userId,
                        authorization = authorization,
                        appDomain = appDomain,
                        deviceDomain = deviceDomain,
                        presetKey = presetKey,
                    )
                )
            },
            enabled = !state.busy,
        ) { Text("连接官方 App SDK") }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ model.refreshEufyDevices() }, enabled = !state.busy) { Text("刷新设备") }
        TextButton({
            model.disconnectEufy()
            appName = ""; clientId = ""; userId = ""; authorization = ""
            appDomain = ""; deviceDomain = ""; presetKey = "US"
        }, enabled = !state.busy) { Text("断开并清凭证") }
    }
    if (state.eufyDevices.isEmpty()) {
        Text("账号下暂未返回设备；这是真实空列表，不生成模拟设备。")
        return
    }
    Choice(
        "设备",
        state.eufyDeviceSn,
        state.eufyDevices.map { it.deviceSn },
        !state.busy,
        model::selectEufyDevice,
    )
    val selected = state.eufyDevices.firstOrNull { it.deviceSn == state.eufyDeviceSn }
    selected?.let {
        Text("${it.displayName}\n型号: ${it.model}\nSN: ${it.deviceSn}\n父设备: ${it.parentSn}\n状态: ${if (it.online) "在线" else "离线"}")
        Text("隐私模式能力: ${if (it.privacyModeSupported) "支持" else "不支持"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ model.fetchEufyEvents() }, enabled = !state.busy && it.online) { Text("查询 24h 事件") }
            OutlinedButton(
                { confirm("privacy-on") },
                enabled = !state.busy && it.online && it.privacyModeSupported,
            ) { Text("开启隐私模式") }
            OutlinedButton(
                { confirm("privacy-off") },
                enabled = !state.busy && it.online && it.privacyModeSupported,
            ) { Text("关闭隐私模式") }
        }
    }
    state.eufyEvents.forEach { event ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("事件 ${event.eventId}")
                Text("类型 ${event.payload.providerType} · ${event.occurredAt}")
                TextButton(
                    { model.fetchEufyThumbnail(event.eventId) },
                    enabled = !state.busy && event.payload.hasThumbnail,
                ) { Text("读取真实缩略图") }
            }
        }
    }
    state.eufyThumbnailBase64?.let { encoded ->
        val bitmap = remember(encoded) {
            runCatching {
                val payload = encoded.substringAfter("base64,", encoded)
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "真实 eufy 事件缩略图", modifier = Modifier.fillMaxWidth())
        } else {
            Text("缩略图返回值无法解码", color = MaterialTheme.colorScheme.error)
        }
    }
}
@Composable private fun Toggle(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Switch(value, change, enabled = enabled) } }
@Composable private fun Choice(label: String, value: String, values: List<String>, enabled: Boolean, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton({ expanded = true }, enabled = enabled) { Text("$label：$value") }; DropdownMenu(expanded, { expanded = false }) { values.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; change(item) }) } } }
}
