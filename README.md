# LookBuddy

LookBuddy 是一个面向独立办公场景的低打扰健康陪伴系统。它使用 eufy 摄像头或电脑摄像头在本地分析姿态与行为，只把语义事件发送给后端，再结合用户偏好生成提醒、反馈闭环和工作日记。

> 当前状态：Web、桌面端、后端和 Android 的代码与自动化验证已经打通；eufy T8171/T8214 真机、赛事账号和现场网络仍需按验收表验证。项目不是医疗产品，也不用于身份识别、考勤或绩效评价。

## 项目组成

| 目录 | 作用 | 当前状态 |
|---|---|---|
| `apps/web` | React Web 端，本地 MediaPipe 感知、eufy Web SDK 直播与裸帧接入 | 已完成代码与自动化验证，待真机 |
| `apps/desktop` | Electron Windows 桌面容器和悬浮桌宠 | 可运行、可打包，待真实摄像头长期验证 |
| `apps/android-local` | Android 产品端、后端客户端和 eufy App SDK 设备能力 | 已接入 SDK 2.2.7，待真机 |
| `apps/backend` | FastAPI 服务、认证、事件、提醒策略、学习、日记和数据删除 | 已完成核心能力 |
| `packages/contracts` | 多端共用的 JSON Schema | 已投入使用 |
| `scripts` | 合约导出、自动检查、演示预检和接口验收脚本 | 已投入使用 |

完整目录说明见 [项目结构](docs/README.md)。

## 双端如何配合

```text
eufy Web SDK / Webcam
  -> 浏览器本地 MediaPipe 分析
  -> 只上传语义事件和低频状态
  -> FastAPI 后端生成提醒、反馈闭环和日记
  -> Web / Windows 桌宠 / Android 展示与操作

eufy Android App SDK
  -> 设备列表、在线状态、事件、缩略图
  -> 用户二次确认后执行隐私模式控制
  -> SDK 数据转换为统一的 lookbuddy.event.v1
```

Web/桌面端负责连续视觉感知；Android 负责赛事要求中的设备状态、视频流之外的信息和受控设备操作。两端共用后端账号、偏好和业务数据，但各自维护本地登录会话。

## 快速开始

### 环境要求

- Node.js 22.12 或更高版本
- pnpm 10.28
- Python 3.11
- Android 开发额外需要 JDK 17、Android SDK 36

### 1. 安装前端依赖

```powershell
pnpm install
```

### 2. 启动后端

```powershell
cd apps/backend
uv sync --dev --no-editable
uv run uvicorn lookbuddy.main:app --app-dir src --reload --port 8000
```

API 文档：`http://127.0.0.1:8000/docs`。

### 3. 启动 Web

```powershell
cd ../../
pnpm dev:web
```

打开 `http://127.0.0.1:5173`。没有赛事凭证时可以使用 Webcam 或明确标记的模拟回放进行开发。

### 4. 启动 Windows 桌面端

```powershell
pnpm dev:desktop
```

### 5. 构建 Android

```powershell
cd apps/android-local
.\gradlew.bat -p integration :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS/Linux 首次运行先执行 `chmod +x ./gradlew`，然后将命令中的 `.\gradlew.bat` 换成 `./gradlew`。Android SDK 凭证只在运行时输入，不写入仓库。

## 自动检查

Windows PowerShell：

```powershell
.\scripts\check.ps1
```

也可以分别运行：

```powershell
pnpm test:web
pnpm test:desktop
pnpm build:web
cd apps/backend
uv run pytest -q
```

## 隐私与功能边界

工位日记支持通过客户端 SDK 按需读取设备已有图片和历史录像；后端只保存事件及媒体引用。查看时会临时加载媒体，保存期限由设备服务决定。完整链路和验收边界见 [日记设备媒体](docs/diary-device-media.md)。

- 默认不把连续视频、原始画面、面部关键点或 SDK 凭证发送到 LookBuddy 后端。
- 不做人脸身份识别，不推断心理疾病、人格、工作态度或工作绩效。
- 提醒删除、账号删除和设备控制都需要明确的用户操作。
- 表情结果只称为“可见表情假设”，不能当作真实情绪或医学结论。
- Router 只改写已经筛选过的事实，不决定提醒、奖励或日记入选内容。

详见 [项目功能边界](docs/project-boundaries.md)。

## 文档入口

- [文档总目录](docs/README.md)
- [当前开发进度与后续计划](docs/development-status.md)
- [功能边界与双端分工](docs/project-boundaries.md)
- [平台与 SDK 依赖](docs/platforms-and-sdks.md)
- [系统架构](docs/architecture.md)
- [eufy SDK 接入说明](docs/sdk-integration.md)
- [Android 真机验收表](docs/android-eufy-device-acceptance.md)
- [领域事件协议](docs/event-contract.md)

## 第三方资产说明

仓库包含主办方提供的 eufy Web SDK 资源和 Android `ESIotKit-2.2.7.aar`，仅用于本次黑客松开发与验收。仓库应保持私有；对外公开或再分发前，必须先确认主办方和 SDK 权利方的授权范围。

