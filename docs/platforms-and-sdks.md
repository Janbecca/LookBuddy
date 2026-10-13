# 平台与 SDK 依赖

## 运行环境总览

| 模块 | 主要技术 | 推荐环境 |
|---|---|---|
| Web | React 18、TypeScript 5.7、Vite 6、MediaPipe Tasks Vision | Node.js 22.12+、pnpm 10.28 |
| Desktop | Electron 44.7、`@electron/packager` 20.3 | Windows x64、Node.js 22.12+ |
| Backend | Python 3.11、FastAPI、SQLAlchemy、SQLite | Python 3.11、uv |
| Android | Kotlin 2.2.21、Compose、AGP 8.11.1、Gradle 8.13 | JDK 17、Android SDK 36、min SDK 26 |

锁定版本以各模块的 `package.json`、`pyproject.toml`、Gradle 文件和 `pnpm-lock.yaml` 为准，本页只用于快速理解。

## eufy Web SDK

- 位置：`apps/web/public/sdk/`
- 来源与校验：`apps/web/public/sdk/manifest.json`
- 主要入口：`OpenAPI connect()`、`OpenAPIIntegration`、`Provider`、`DeviceLiveLayout`、`subscribeMediaChannelFrames`
- 用途：赛事鉴权、Live 画面和解码后的裸帧订阅。
- 边界：本次 Web SDK 不承担设备事件、回放或设备控制。

Web 运行时需要：`appName`、`clientId`、`clientSecret`、`presetKey`、`eufyUserId`、`accountUuid`、`stationSn`。默认只保存在当前会话；只有用户明确选择时才使用浏览器长期存储。

## eufy Android App SDK

- 文件：`apps/android/app/libs/ESIotKit-2.2.7.aar`
- 版本：2.2.7
- 大小：94,107,807 bytes
- SHA-256：`27916001D9F973361910E2A59DA3A870F540F3038816DFC6141B3D33C633F87A`
- 用途：设备列表和在线状态、24 小时事件查询、事件缩略图、隐私模式控制。

Android 运行时需要：`appName`、`clientId`、`userId`、`authorization/token`、HTTPS `appDomain`、HTTPS `deviceDomain`、`presetKey`。这些值只保留在当前进程内，成功连接后清空 token 输入框，断开后清空 SDK 会话。

AAR 自带或依赖 Ktor、Koin、AWS IoT、Paho MQTT、Room、SQLDelight、Conscrypt 等运行库。完整依赖和冲突固定策略见 `apps/android/app/build.gradle.kts`。不要手动解压、换行转换或修改 AAR。

## MediaPipe

- npm 包：`@mediapipe/tasks-vision`
- 模型：`apps/web/public/models/`
- WASM：`apps/web/public/wasm/`
- 运行位置：浏览器或 Electron 渲染进程本地。

模型输出只用于有限的姿态、动作和可见表情假设；它不是身份识别或医疗模型。

## AI Router

后端通过以下环境变量连接主办方 Router：

```text
LOOKBUDDY_ROUTER_BASE_URL
LOOKBUDDY_ROUTER_API_KEY
LOOKBUDDY_ROUTER_MODEL
```

未配置时使用确定性中文模板。Router 只负责文字表达，不决定业务事实。

## 后端与客户端配置

- 本地后端默认：`http://127.0.0.1:8000`
- Android 模拟器访问宿主机：`http://10.0.2.2:8000/`
- Android 真机：使用同一受信局域网内可访问的主机地址，正式环境必须使用 HTTPS。
- 示例环境变量见根目录 `.env.example`，真实 `.env` 文件已被 Git 忽略。

## 第三方资产与仓库可见性

Web SDK 和 Android AAR 来自主办方资料包，不是 LookBuddy 自有开源代码。当前 GitHub 仓库应设为私有。公开仓库、发布安装包或把二进制交给第三方前，需要先确认授权与再分发条件。

