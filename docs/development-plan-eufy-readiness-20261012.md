# LookBuddy eufy 真设备与赛事验收开发计划

日期：2026-10-12  
状态：执行中（M0 完成；M1/M2/M3 已完成代码与自动化验证，等待真机验收）  
目标：在保留现有 LookBuddy 低打扰健康陪伴闭环的基础上，接入赛事官方 eufy SDK 和真实设备，补齐隐私治理、提交物与现场验收，使项目从“自有业务 Demo 可运行”达到“满足黑客松硬要求并可稳定路演”。

> 阅读提示：第 1 节保留的是 2026-10-12 制定计划时的起点，不代表当前状态。M1/M2/M3 的代码与自动化验证已经完成；当前状态以 `docs/development-status.md` 为准。

## 1. 制定计划时的基线

### 已完成

- FastAPI 后端、用户认证、配置、提醒、动作闭环、学习策略、日记和数据删除接口。
- Web/桌面端的 Webcam + MediaPipe 姿态、久坐、饮水、疲劳和可见表情假设。
- Windows Electron 便携版和 Android API 调试客户端。
- 自动化基线：后端 49 项测试通过；Web 23 项通过、1 项集成测试因未配置后端跳过；桌面 6 项通过；Android APK、单测和 Lint 构建成功。

### 制定计划时尚未满足

- Web 正式输入仍是电脑 Webcam，`EufyWebSdkFrameSource` 为抛错占位。
- Android 当时的 `EufySdkGateway` 为 Mock，官方 `ESIotKit-2.2.7.aar` 尚未接入；该项现已完成代码与自动化验证。
- 没有真实设备控制、事件查询/监听、缩略图和在线状态证据。
- 现有 Web SDK 接入文档仍基于旧版接口，与资料包的 `Provider`、`DeviceLiveLayout`、`subscribeMediaChannelFrames` 不一致。
- 缺少数据治理架构图、明确的数据留存执行机制、知情同意/未成年人方案和下一代硬件建议卡。
- 尚未完成 T8171/T8214 真机、Android UI、断流重连和正式 6 分钟路演验收。

## 2. 技术路线决策

采用双端互补方案：

```text
T8171 / T8214
  ├─ Web SDK Live + subscribeMediaChannelFrames
  │    → 本地 MediaPipe 感知
  │    → 只上传语义事件/低频状态
  │    → LookBuddy 后端与桌宠闭环
  │
  └─ Android App SDK
       → 设备列表与在线状态
       → 事件查询/事件监听
       → 缩略图读取
       → 人工确认的设备控制
       → 映射为 LookBuddy 领域事件
```

选择原因：

- Web SDK 最新 Demo 明确提供 Live 裸帧，适合复用现有 MediaPipe 引擎。
- Web 指南同时说明控制、事件和回放不在本次 Web 范围内；Android SDK 是满足“设备控制 + 视频流之外信息”硬要求的确定路径。
- 原始视频继续在终端本地处理；后端不接收连续帧或人脸关键点。
- Android 与 Web 可以独立降级：一端短暂断开不导致整个演示不可恢复。

## 3. 执行顺序

## M0：资料基线与安全边界

目标：先消除旧文档和错误假设，固定后续实现依据。

任务：

- 更新 `docs/00-organizer-constraints.md`：记录资料包当前的 Web/App 能力、接口和已知矛盾。
- 更新 `docs/sdk-integration.md`：将旧版 `init/createTransport/startStream/LocalPlayer` 方案替换为当前 Demo 的接入方式。
- 建立凭证清单，但不写入仓库：
  - Web：`appName`、`clientId`、`clientSecret`、`presetKey`、`eufyUserId`、`accountUuid`、`stationSn`。
  - Android：`appName`、`userId`、`authorization/token`。
  - AI Router：Base URL、API Key、模型 ID。
- 扩充 `.gitignore`，覆盖本地凭证、官方二进制和现场配置文件。
- 记录官方 SDK 二进制来源、版本、大小和校验值，防止错包或现场被覆盖。

验收：

- 文档不再出现“官方材料尚未获得”的错误描述。
- 仓库扫描不到真实凭证。
- Web SDK 与 Android AAR 的版本和来源可追溯。

## M1：Web eufy SDK 加载与鉴权

目标：在 LookBuddy Web 中显示真实 eufy Live 画面，并能稳定重连。

任务：

- 将官方 `public/sdk/index.js` 和 i18n 资源放入 LookBuddy Web 的受控资源目录。
- 引入并收敛官方 Demo 的以下能力：
  - UMD SDK 动态加载。
  - React/ReactDOM 全局挂载。
  - OpenAPI 四步鉴权 `connect()`。
  - `Provider + DeviceLiveLayout`。
  - 开发环境 OpenAPI 代理。
- 将 SDK Provider 子树移出 React Strict Mode，避免 transport 和签名请求双初始化；其余应用可继续保留 Strict Mode。
- 增加 eufy 连接页：配置、连接进度、在线/断流状态、重新连接和主动断开。
- 凭证只保存在用户明确选择的本地存储中；默认会话内存保存，界面提供“清除设备凭证”。
- 对设备主动断流、错误 SN、鉴权失败、浏览器不支持和 SDK 加载失败提供可操作提示。

计划文件：

- `apps/web/src/eufy/sdk-loader.ts`
- `apps/web/src/eufy/connect.ts`
- `apps/web/src/eufy/types.ts`
- `apps/web/src/eufy/EufyLiveProvider.tsx`
- `apps/web/src/eufy/EufyConnectionPanel.tsx`
- `apps/web/vite.config.ts`
- `apps/web/src/main.tsx`

验收：

- T8171/T8214 能完成鉴权并显示 Live 画面。
- 设备主动断流后可一键重连，无需重新输入全部凭证。
- 组件卸载和主动断开后 transport 被释放。
- 错误凭证、错误 SN 和无设备场景不会导致白屏。

## M2：eufy 裸帧接入 LookBuddy 感知引擎

目标：以真实 eufy 画面替换 Webcam，复用现有 MediaPipe 和业务闭环。

任务：

- 使用 `subscribeMediaChannelFrames(stationSn, onFrame, options)` 获取解码后图片。
- 将 `Blob` 异步转换为 `ImageBitmap` 或受控 Canvas，提供给 `MediaPipeVisionEngine`。
- 将视频源抽象从“只支持同步 HTMLVideoElement”升级为可处理异步帧：
  - 明确 start/stop 生命周期。
  - 丢弃旧帧，避免处理积压。
  - 确保 ImageBitmap、Blob URL 和订阅函数被释放。
  - 按设备性能限制到合理帧率，初始建议 2～5 fps 做本地 CV。
- 增加 `webcam` / `eufy_web_sdk` 显式模式选择；正式路演默认 eufy，Webcam 仅作为离线开发降级。
- 修正所有上报数据中的 `source`，不再硬编码 `webcam`。
- eufy 原始帧不得进入 LookBuddy 后端；仅上传已有语义事件、低频状态和用户主动确认的数据。
- 为断流、空帧、解码错误、处理超时和取消订阅补充测试。

计划文件：

- `apps/web/src/vision/frame-source.ts`
- `apps/web/src/vision/eufy-frame-source.ts`
- `apps/web/src/App.tsx`
- `apps/web/src/types.ts`
- 对应 Vitest 测试文件

验收：

- 页面可证明当前输入源为真实 eufy 设备。
- eufy 画面可以驱动姿态/久坐等现有感知结果。
- 网络断开、停止观察或退出程序后不再持续处理帧。
- 浏览器内存不会因未释放 ImageBitmap/Blob URL 持续增长。

## M3：Android App SDK 真设备能力

目标：无歧义满足赛事“设备控制 + 视频流之外信息”硬要求。

任务：

- 将 `ESIotKit-2.2.7.aar` 放入 `apps/android/app/libs/`，记录版本和校验值。
- 对齐官方工程：Kotlin/AGP/JDK 版本、packaging、JNI pickFirst、Koin/依赖冲突处理和 ProGuard 规则。
- 按实际使用能力补齐 Manifest 和运行时权限，不申请未使用权限。
- 重写 `EufySdkGateway`：
  - `ESIotKit.start(ESIotAppConfig)`。
  - `ESIotKit.setUserConfig(ESIotUserConfig)`。
  - 获取设备列表、设备 SN、型号与在线状态。
  - 事件查询；条件允许时增加事件监听并切回主线程更新 UI。
  - 缩略图路径读取与 `GetESIotImage` 转换。
  - 至少一个能力检测后的设备控制。
- 首选演示控制：
  - T8171：PTZ/夜视/隐私模式中选择现场最稳定的一项。
  - T8214：灯光或其他能力矩阵明确支持的控制。
- 所有设备控制必须由用户点击并二次确认；调用前检查 capability、设备在线、摄像头状态和隐私区限制。
- 为 ACK 延迟、30 秒超时、`-1002/-1003/-1004`、设备离线和物模型未就绪提供等待与恢复状态。
- 将官方设备事件映射为 `lookbuddy.event.v1`，SDK 类型不泄漏进领域模型。

计划文件：

- `apps/android/app/libs/ESIotKit-2.2.7.aar`
- `apps/android/app/build.gradle.kts`
- `apps/android/app/src/main/AndroidManifest.xml`
- `apps/android/app/src/main/java/com/lookbuddy/app/sdk/EufySdkGateway.kt`
- `apps/android/app/src/main/java/com/lookbuddy/app/sdk/RealEufySdkGateway.kt`
- Android 设备页、事件页和控制确认 UI

验收：

- 真机显示已绑定设备、SN、型号和在线状态。
- 事件查询至少成功返回空列表或真实事件列表。
- 触发一次运动/门铃事件后能查询到或收到回调。
- 能读取并展示一张真实事件缩略图。
- 一个设备控制在人工确认后成功，设备状态发生可观察变化。
- 控制失败不会显示伪成功，超时可重试。

执行结果（2026-10-13）：

- 已接入官方 `ESIotKit-2.2.7.aar`，SHA-256 为
  `27916001D9F973361910E2A59DA3A870F540F3038816DFC6141B3D33C633F87A`。
- 已对齐官方 Android 工具链：JDK 17、Gradle 8.13、AGP 8.11.1、
  Kotlin 2.2.21、compile/target SDK 36、min SDK 26。
- 已完成内存鉴权、设备/型号/在线状态、24 小时事件查询、事件缩略图和隐私模式控制。
- 控制前检查在线状态、物模型 capability 与写权限；UI 必须二次确认。
- `-1002/-1003/-1004`、30 秒 ACK 超时、离线及空设备/空事件均显示真实恢复提示，不伪造成功。
- Android 自动验证通过：APK 构建、12 项单测通过，1 项后端集成测试因未配置地址跳过，
  Lint 0 error。真机设备、账号环境、控制状态变化和缩略图显示仍必须按 M6 单独验收。

## M4：隐私、数据治理与行为画像风险收敛

目标：让治理要求成为产品能力，而不是口头说明。

任务：

- 增加首次启动知情同意：处理目的、数据流、留存时间、第三方 AI、撤回方式。
- 增加“仅限本人自我健康辅助”声明：不用于考勤、绩效、雇主管理或识别第三人。
- 增加被拍摄者提示和未成年人保护：
  - 默认不允许在未知第三人在场时持续分析。
  - 未成年人场景禁用或要求监护人明确授权。
- 将画面、图像、语义事件、表情特征、干预奖励、日记分别定义留存策略。
- 把 `retention` 从自由字符串改为受控枚举，并在服务端落实自动清理/TTL，而非只保存标签。
- 增加可见的数据管理页：查看数据类别、导出/删除日记、清除观察历史、清除学习数据、删除账号。
- 默认关闭表情陪伴和长期学习，由用户主动开启。
- 保留“可见表情假设”措辞，禁止输出心理疾病、工作态度或人格判断。
- 绘制赛事数据治理架构图，明确：数据去了哪里、保存多久、谁能访问。

验收：

- 未同意前不启动摄像头、不连接设备、不上传语义数据。
- 用户可撤回同意并停止采集。
- 自动清理策略有后端测试，过期数据会真实删除。
- Web 和 Android 都有可发现的删除入口。
- 数据治理图与实际代码行为一致。

## M5：赛事提交物与演示体验

目标：将技术能力组织成评委可以在 6 分钟内验证的作品。

任务：

- 编写 6 分钟正式演示稿：
  1. 用户与真实问题。
  2. eufy 设备连接、在线状态和控制。
  3. eufy 裸帧驱动 LookBuddy 本地感知。
  4. 低打扰提醒、用户确认和动作闭环。
  5. 事件/缩略图作为视频流之外信息。
  6. 数据治理和下一代硬件建议。
- 增加演示模式，但必须清楚标记模拟数据，不能与真设备结果混淆。
- 完成下一代硬件建议卡，建议必须由 Demo 中的真实证据支撑，例如：
  - 设备侧低功耗语义特征输出，减少连续视频传输。
  - 面向室内陪伴场景的桌面安装形态和明确的录制指示灯。
  - 可由被拍摄者物理关闭的隐私按键/遮挡结构。
  - 更稳定的帧订阅和设备状态 API。
- 更新 demo runbook、现场网络清单、凭证清单和故障降级流程。
- 生成最终 Windows 便携包和 Android APK，并固定校验值。

验收：

- 6 分钟内完成全链路，至少预留 30 秒处理现场波动。
- 演示者可以指着治理图回答三项必答问题。
- 下一代硬件建议能对应到一次实际演示或测量证据。
- 断网、设备离线、Web 断流、Android 控制超时都有明确降级话术。

## M6：真机与现场验收

目标：只以现场可重复证据判定完成，不以单元测试替代硬件验收。

验收矩阵：

| 边界 | 最低通过标准 |
|---|---|
| Web 鉴权 | 正确凭证连接成功；错误凭证给出可理解错误 |
| Live 画面 | T8171/T8214 至少一款稳定显示并可重连 |
| 裸帧 | 能连续收到有效图片，并驱动至少一个 LookBuddy 感知结果 |
| 设备状态 | Android 显示设备在线/离线并与现实一致 |
| 事件 | 查询或监听到真实设备事件 |
| 缩略图 | 获取、读取并显示真实事件缩略图 |
| 设备控制 | 人工确认后成功控制一次，失败不伪成功 |
| 隐私模式 | 停止后释放流和相机；原始帧不上传后端 |
| 数据删除 | 清除历史/删除账号后数据库实际无关联记录 |
| 稳定性 | 连续演示 15 分钟，无不可恢复白屏或资源泄漏 |
| 安装 | 新 Windows 环境和一台 Android 真机可按文档安装运行 |

最终命令检查：

```powershell
cd LookBuddy-main
.\scripts\check.ps1
```

真机验收必须单独记录设备型号、固件、手机型号、Android 版本、网络条件、账号环境、执行人、时间和结果。

## 4. 优先级与停止线

### P0：不完成不能参赛验收

- M0 资料基线。
- M1 Web 真设备取流。
- M2 eufy 裸帧驱动 LookBuddy。
- M3 Android 状态、事件、缩略图和设备控制。
- M4 最低治理要求与治理图。
- M5 正式提交物。
- M6 至少一款真实设备全链路通过。

### P1：提高技术可靠性和评审表现

- 多设备型号能力矩阵和动态 capability UI。
- Android 实时事件监听与 MQTT/P2P 通道说明。
- 自动留存清理、数据导出和更完整的治理 UI。
- 断流自动恢复、健康状态和现场诊断页。

### P2：时间充足再做

- iOS 客户端。
- 更复杂的 PTZ/预置位、事件视频回放。
- 云端视觉模型增强。
- 多账号、多摄像头和远程部署。

停止线：在 P0 全部完成前，不新增新的情绪标签、推荐策略、桌宠动画或非验收必需的 UI 功能。

## 5. 完成定义

只有同时满足以下条件，才能把项目标记为“赛事准备完成”：

1. 至少一款官方设备完成真实取流、事件/状态、缩略图和人工确认控制。
2. Web 的正式感知输入来自 eufy SDK，而不是 Webcam 或模拟回放。
3. 项目在无真实凭证进入仓库的情况下可按文档配置运行。
4. 数据治理图、实际留存机制、删除入口、知情同意和未成年人方案一致。
5. 全量自动化检查通过，且真机验收矩阵有记录。
6. 6 分钟演示经过至少两次完整计时排练。
7. 下一代硬件建议卡有真实 Demo 证据支持。

