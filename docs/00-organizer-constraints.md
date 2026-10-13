# 主办方开发约束基线

最后复核：2026-10-12

本地权威来源：`【黑客松智能安防赛道】工具资料包`，包括赛题规则、Web/App 开发指南及官方 Demo。仓库实现以本地材料为准，不再依赖旧在线链接的接口记忆。

## 平台与工具

| 平台 | 官方约束 | 本项目落实 |
|---|---|---|
| Web | Node 18+、pnpm、TypeScript、Vite、React | `apps/web` 使用 React 18、TypeScript 5、Vite 6 |
| Android | JDK 17、compileSdk >= 34、minSdk <= 24 | JDK 17、`compileSdk=36`、当前 `minSdk=26`；App SDK 2.2.7 已接入，但最低系统版本与赛事基线仍有差距，发布前必须确认或兼容到 API 24 |
| AI | 主办方 Router，兼容 OpenAI / Anthropic 协议 | 仅后端读取环境变量并调用 |

## SDK 能力边界

### Web SDK

- 当前官方 Demo 的链路为 OpenAPI `connect()` → `OpenAPIIntegration` → `Provider` → `DeviceLiveLayout`。
- 本次开放实时取流/播放；控制、事件和回放不作为 Web SDK 能力承诺。
- 官方 UMD 包额外提供 `subscribeMediaChannelFrames(stationSn, onFrame, options)`，可取得解码后的 JPEG/PNG/WebP Blob，供终端侧 AI 使用。
- LookBuddy 将 Blob 转成 `ImageBitmap` 后在浏览器本地运行 MediaPipe，只向后端发送语义事件和低频状态。
- 支持的赛事设备范围按资料限定为 T8171 / T8214。

### Android App SDK

- App SDK 2.2.7 提供设备信息、在线状态、事件/缩略图及能力受控的设备控制，是满足“视频流之外信息 + 设备控制”的主路径。
- 推荐从官方 Android Demo 和 fat-AAR 对齐 Gradle、初始化、权限及 JNI 配置。
- 设备控制必须由明确用户操作触发，并在调用前检查 capability、在线状态及隐私限制。
- 局域网能力依赖手机与设备处于可互通网络；现场须提前验证 AP 隔离等网络条件。

## 规则材料中的口径差异

总规则要求作品使用设备控制及视频流之外信息，而 Web 指南只承诺 Live 能力。项目采用 Web 感知 + Android 控制/事件的双端方案，同时满足两组要求，不假设 Web SDK 存在未公开控制接口。

## 安全与产品红线

- 不识别具体身份，不输出心理疾病、人格或工作态度判断。
- 原始视频、生物特征和连续关键点默认不上传 LookBuddy 后端。
- 报警删除和设备控制必须由人确认。
- 不破解固件，不做 eufy 产品换皮。
- 凭证不得提交仓库；Web 默认只存当前会话，长期保存必须由用户显式选择。

## 已收到与仍需现场提供的材料

已收到：Web SDK/Demo、App SDK 2.2.7 AAR/Demo、Web/App 开发指南和赛题规则。

仍需现场提供：赛事账号与鉴权参数、可用 T8171/T8214 真机、真实网络环境、Router API Key/模型 ID。这些均通过运行时配置接入，不进入领域模型或源码。

## 二进制追溯

| 资产 | 大小 | SHA-256 |
|---|---:|---|
| Web `public/sdk/index.js` | 以 `apps/web/public/sdk/manifest.json` 为准 | `316BFC1EDB3CF5C35ED24E7FA9FD0CAA1867BC111181C9A58348F86DEE97F02E` |
| Android `ESIotKit-2.2.7.aar` | 94,107,807 bytes | `27916001D9F973361910E2A59DA3A870F540F3038816DFC6141B3D33C633F87A` |

