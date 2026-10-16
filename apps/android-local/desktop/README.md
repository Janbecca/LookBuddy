# LookBuddy Windows 桌宠 MVP

透明、置顶、可拖拽的 Windows 桌宠，直接播放设计资产中的 45 帧透明 WebP，并在 `5211` 端口提供 Android 本地同步接口。

## 启动

需要 Windows、Python 3.11+ 和 Pillow 11.3.0。

- Android 模拟器：双击 `start.bat`。
- Android 真机：手机与电脑连接同一 Wi-Fi，双击 `start-phone.bat`；Windows 防火墙弹窗只允许专用网络。

启动脚本会生成本机随机 token，并只写入 `%LOCALAPPDATA%/LookBuddy/desktop-token` 与项目根目录中不会提交的 `local.properties`。修改本地配置后必须重新构建、安装 Android App。

```powershell
$env:JAVA_HOME='D:/Everything/Anker/Android studio/jbr'
./gradlew.bat :app:assembleDebug
```

## 操作

- 左键拖动：移动桌宠。
- 双击：显示一句对话。
- 右键：测试提醒、隐藏气泡或退出。
- 位置保存在 `%LOCALAPPDATA%/LookBuddy/desktop.json`。

## 本地接口

- `GET /health`：无需 token 的健康检查。
- `GET /v1/state`：读取桌面状态。
- `POST /v1/sync`：Android 上报场景、工作状态和关心语。
- `POST /v1/reminders`：显示一条桌面提醒。

除健康检查外，请求必须携带 `X-LookBuddy-Token`。这套接口用于开发期局域网联调，后续迁移 AWS 时保留相同 JSON 事件模型，并将本地 URL/token 替换为 Cognito + HTTPS/WebSocket。

手动测试提醒：

```powershell
$token = Get-Content "$env:LOCALAPPDATA/LookBuddy/desktop-token"
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:5211/v1/reminders `
  -Headers @{'X-LookBuddy-Token'=$token} `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{"message":"起来伸个懒腰吧~"}'
```
