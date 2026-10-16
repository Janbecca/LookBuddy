# 前端功能核实与开发迭代计划

日期：2026-10-17  
状态：已获授权执行；I1/I2 代码与自动化已完成，I5 真机/发布门禁待验收  
范围：Android 新 UI、后端既有 Agent 能力、LAN 桌宠及相关产品化缺口

执行记录：[2026-10-17 前端迭代交付与验收](../progress/16-frontend-feature-iteration.md)。本轮保留另一任务已有的设备媒体引用实现；本文第 2 节为实施前审计基线，当前状态以执行记录为准。I3 继续遵守核心能力、真机与产物门禁，未启动真实聊天。

## 1. 结论

用户列出的八项判断整体属实，但需要三处重要修正：

1. “日记准备好了”目前只是可预览、可在测试中注入的前台首页提醒 UI，不是生产通知，更不是 Android 系统通知或推送。
2. 后端虽然存在 WebSocket，但 Web 和 Android 当前都没有生产消费端；Web 的真实提醒闭环主要依赖 HTTP snapshot 和 pending polling。因此 Android 接入必须以 HTTP 补查为可靠基线，WebSocket 只能作为加速通道。
3. Android 相机目前只有官方 Eufy `LiveMediaPlayer` 预览，没有分析帧、MediaPipe、时序判断、事件上传或状态快照。它不是“识别已做但尚未上报”，而是整条 Android 感知链尚未实现。

比赛前推荐的产品主线是：

```text
Web / Electron 本地感知
→ 后端事件、状态快照与提醒策略
→ Android 认领并展示提醒
→ 接受 / 稍后 / 忽略
→ 后端动作验证、奖励与日记
```

Android 继续作为移动交互和 Eufy 设备管理端，不在赛前增加第二套连续视觉 Agent。

## 2. 八项能力核实

| 功能 | 核实结论 | 当前真实状态 | 主要缺口 | 建议优先级 |
|---|---|---|---|---|
| Buddy 聊天 | 属实 | 有完整聊天视觉壳、opening、草稿恢复和本页未发送列表；没有网络发送 | 会话/消息模型、认证接口、幂等发送、AI/模板回复、历史、发送状态和重试 | P1，条件实施 |
| 三种新增心情 | 属实 | 好奇、思考、振奋是 `mood=null` 的视觉选项；四种旧心情已走真实 API | 客户端未知值兼容、七值合同、后端合法值、Schema 和端到端测试 | P0 |
| 日记图片/视频回看 | 属实 | 设计示例使用内置图片；真实时间线只有文本；Mark 明确记录 `media=not_recorded` | 采集、媒体元数据、存储、事件关联、读取、播放、删除和隐私生命周期 | P3/赛后 |
| 每日幸运签 | 属实 | 保存合法心情后显示固定“今日宜缓”文案；无日期、账号或心情生成逻辑 | 若需要变化，可做客户端确定性文案池；跨端历史才需要后端 | P2 |
| “日记准备好了” | 基本属实，名称需收紧 | 有 `DiaryReady` 展示模型、跳转和测试注入；生产未注入 source | 同步生成只需成功反馈；若做异步事件，需 eventId、diaryDate、补查、去重和已读 | P2 |
| 桌宠正式配对 | 属实 | debug 构建期写入 URL/token，每 2 秒向 LAN `/v1/sync`；release 默认关闭 | 选定唯一桌宠、运行时配对、设备凭据、撤销轮换、安全传输、连接状态 | P3/赛后 |
| 首页自动提醒 | 属实 | Android 有展示壳和 source 接口，但生产入口传 `null`；后端提醒/反馈接口真实存在 | Android repository、pending 补查、displayed 认领、respond、账号隔离、恢复 | P0 |
| Android 相机姿态/行为识别 | 表格低估缺口 | 当前只有 Eufy 实时预览和预留状态字段；完整感知在 Web/Electron | 先确认可分析帧 API，再做端侧模型、时序规则、事件/snapshot/sample 上报与真机校准 | P4/赛后 |

### 2.1 Buddy 聊天

当前 `BuddyChatState` 只保存 `draft`、`opening` 和 `unsent`。提交动作只是把文本加入本地列表并显示“暂未发送”；没有 Chat ViewModel、repository、客户端接口或后端 Chat 模型。`POST /v1/buddy/greeting` 是单条关心语，不是多轮聊天；日记生成使用的模型调用也不是客户端聊天 API。

准确口径：**聊天视觉壳和草稿已完成，真实聊天能力未实现。**

### 2.2 三种新增心情

数据库字段本身可以容纳新字符串，主要限制位于 Android enum、后端 `MoodIn` 合法值和共享 JSON Schema。需要先解决旧客户端的 `Mood.valueOf()` 强解析风险，否则新版账号写入新值后，旧版 App 可能整次同步失败。

准确口径：**四种心情已经云端持久化，三种新心情仅有 UI 资源。**

### 2.3 日记媒体

真实日记模型没有媒体关系；`/v1/marks` 和 `/v1/diary-page/{day}` 明确返回 `media=not_recorded`。Eufy Android 链路只做 Live 预览，不提供当前产品可用的截图/录像链路。

准确口径：**示例关键帧用于展示设计效果，真实产品当前是结构化文本日记。**

### 2.4 每日幸运签

签文是客户端硬编码内容，显示条件只是当天已成功保存一个合法心情。它不是每日生成、随机生成或 AI 个性化结果。

准确口径：**固定暖心签，不宣称每日变化或 AI 生成。**

### 2.5 日记完成提醒

现有日记生成接口同步返回结果，用户点击生成后可以直接刷新页面。因此当前主流程只需要明确的成功反馈，不需要伪造异步通知。只有未来增加自动生成或后台异步生成时，才需要 `diary.ready` 事件、持久化补查和系统通知。

准确口径：**目前是可注入的前台提醒 UI，不是生产通知。**

### 2.6 桌宠配对

当前 token 是 debug 构建配置，并通过局域网 HTTP 发送。虽然服务使用高熵 token、恒时比较和请求体上限，但没有一次性握手、设备身份、过期、撤销、轮换、TLS 或 Android Keystore。服务监听 `0.0.0.0`，`/health` 无鉴权，不能称为正式配对。

准确口径：**LAN debug 联动已实现，正式配对未实现。**

### 2.7 Android 首页提醒

后端已经具备：

- `GET /v1/reminders/pending`；
- displayed 认领；
- accepted/snoozed/ignored；
- Intervention 查询和动作结果；
- 持久化延后调度；
- `/v1/stream` WebSocket。

但 Android 当前没有这些 endpoint 的客户端实现。后端 WebSocket 是单进程内存连接，定时重新派发也不保证 publish；因此不能只接 WebSocket，必须保留 pending polling。

准确口径：**后端 Agent 能力已经存在，Android 生产消费链尚未接通。**

### 2.8 Android 姿态/行为感知

`EufyCameraFeed` 只创建 `LiveMediaPlayer` 并显示预览；没有连续解码帧、模型依赖、姿态/行为状态、事件上传或 snapshot。Web/Electron 已有 MediaPipe 和有限时序启发式，应继续作为赛前唯一连续感知端。

准确口径：**Android 官方设备预览已接入，Android 本地视觉识别未实现。**

## 3. 范围决策

### 3.1 比赛前必须完成

1. Android 真实提醒消费与响应。
2. 七种心情全链路，并兼容未知/旧值。
3. 一款真实 Eufy 设备与 Android 真机的主链验收。
4. 隐私同意、暂停和清除边界不因新功能退化。
5. 固定 APK、Windows 包和云端 SHA，完成计时排练。

### 3.2 有余量再完成

1. Buddy 非流式单会话 MVP。
2. 客户端确定性幸运签文案池。
3. 日记同步生成后的明确成功反馈。
4. WebSocket 作为 Android 提醒加速通道。

### 3.3 比赛后完成

1. 图片/视频日记媒体链路。
2. 桌宠正式扫码配对、TLS、撤销和凭据轮换。
3. Android 本地 MediaPipe/视觉识别。
4. FCM/系统通知、后台自动日记调度。
5. 流式多会话聊天、附件和长期记忆。

## 4. 依赖关系

```text
现有后端 Reminder/Intervention API
        |
        v
I1 Android pending/displayed/respond
        |
        +--> 双端提醒主链验收
        `--> 可选 WebSocket 加速

I2 未知 Mood 容错
        v
后端七值合同与 Schema
        v
Android 七值保存和回读

I3 Chat 数据模型与幂等接口
        v
Android 发送状态、历史和重试
        v
模型/模板回复与隐私清理

同步日记生成 --> 页面成功反馈
异步/自动日记 --> diary.ready + pending + acknowledge

正式桌宠配对、日记媒体、Android 视觉均独立进入赛后产品化路线
```

## 5. 迭代计划

### I0：冻结口径和接口基线

预计：0.5–1 人日  
目标：避免在开发过程中继续把预览、静态内容或 debug 能力当成真实产品能力。

任务：

- 确认比赛主链由 Web/Electron 负责感知，Android 负责移动交互和设备管理。
- 确认比赛是否必须展示真实 Buddy Chat；若不是，保持“未连接”诚实状态。
- 确认独立 LAN 桌宠仅作为 debug/demo 联动，不在本轮承诺正式配对。
- 固定 Reminder/Intervention/Mood 当前 OpenAPI 和 JSON Schema 基线。
- 固定一组跨端演示账号和不含隐私数据的验收数据。

完成标准：

- 演示稿、状态页和 UI 文案不再出现能力夸大。
- 每项赛前功能都有负责人、验收人和截止时间。

### I1：Android 真实提醒消费

预计：2–4 人日  
优先级：P0  
目标：Web/Electron 触发的真实提醒可以在 Android 前台被可靠认领、展示和响应。

后端工作：

- 复核 pending、displayed、respond 和 intervention response 的字段是否足够映射 Android UI。
- 为 Android 客户端需要的字段补齐共享 Schema；不重复实现第二套提醒规则。
- 补 WebSocket 业务消息投递测试，但不把它作为唯一可靠通道。
- 明确 scheduler 重新派发后依赖 pending polling 恢复。

Android 工作：

- 新建账号作用域的 `ReminderRepository`，登录切换时取消旧任务并清空内存状态。
- App 前台每 10–15 秒查询 pending；恢复前台和网络重连时立即补查一次。
- 收到提醒后先调用 displayed 认领，只有 2xx 才交给 Home UI；409 表示已被其他端处理，不再显示。
- 扩展 `HomeReminderSource`，表达 displayed、accepted、snoozed、ignored、请求中、失败和重试，而不是只有本地 acknowledge。
- 将四种行为提醒映射到现有首页卡片；保留稳定服务端 ID，不生成 preview ID。
- 接入接受、稍后、忽略；失败时显示真实状态，不做乐观伪成功。
- 可选增加 WebSocket 前台连接作为即时触发，断线后继续使用 polling。

验收：

1. Web/Electron 上传真实或明确标记的测试 snapshot，后端产生一个 reminder。
2. Android 在轮询周期内显示；displayed 在服务端只登记一次。
3. Android 分别完成接受、稍后、忽略，后端状态正确。
4. Web 和 Android 同时竞争时只有一个端认领成功，另一端处理 409 且不重复弹出。
5. Android 断网后恢复、切后台再回来、令牌刷新和切换账号均不会串提醒。
6. 延后到期即使没有 WebSocket publish，也能通过 pending 补查恢复。

### I2：七种心情全链路

预计：1–2 人日  
优先级：P0  
目标：好奇、思考、振奋与现有四种心情一样真实保存、同步和回读。

实施顺序：

1. 先将 Android Mood 解析改为容错映射，未知服务值不导致整次同步失败。
2. 扩展 Kotlin enum 和 UI option，移除三项 `mood=null` 分支。
3. 扩展后端 `MoodIn` 合法值和共享 `MoodIn.v1.json`。
4. 保持现有 `DailyMood` 表；当前 `String(16)` 足以容纳新值，无需新表。
5. 增加后端七值参数化测试、Android 七值 round-trip、未知值兼容及旧四值回归。
6. 更新日记/统计对新心情的展示映射；不自动把主观心情当作表情观察或学习奖励。

验收：

- 七种心情都能保存、刷新、跨端回读和删除。
- 旧四值账号不迁移也能正常读取。
- Android 遇到未知未来值不会崩溃或中止整个 sync。
- 保存失败时 UI 不显示成功签文或伪造本地成功。

### I3：Buddy Chat 单会话 MVP

预计：3–5 人日  
优先级：P1，比赛脚本需要时实施  
目标：把现有聊天视觉壳接成真实、可失败、可恢复的非流式单会话聊天。

最小后端：

- 新增账号级 Conversation/Message 模型，消息包含 role、content、createdAt 和状态。
- `POST /v1/buddy/messages` 接收 `clientMessageId` 和 text，重复提交不重复创建用户消息或模型回复。
- `GET /v1/buddy/messages?limit=` 返回最近 N 条，不做会话列表。
- 模型只接收允许的低敏上下文；不读取原始视频、关键点或未经确认的长期记忆。
- 模型不可用时返回明确错误或经过审核的模板，不伪造 AI 成功。
- 账号删除和聊天清理覆盖 Conversation/Message。

最小 Android：

- 将 `unsent` 替换为 pending/sending/sent/failed 状态。
- 登录后加载最近历史；按账号隔离草稿和请求。
- 失败消息可点击重试，并复用原 `clientMessageId`。
- 页面离开或进程恢复后能显示服务端已确认消息。
- 本轮不做流式 token、附件、多个会话、搜索和长期记忆。

验收：

- 用户消息和 Buddy 回复刷新后仍存在。
- 网络超时重试不产生重复消息。
- 模型不可用、限流和非法输入均有明确错误状态。
- 删号或清聊天后不能再查询历史。

停止条件：若 I1、I2、真机验收或正式产物尚未完成，不启动 I3。

### I4：低风险体验补齐

预计：1–2 人日  
优先级：P2

#### 幸运签

- 将产品名称改为“今日小签”或“固定暖心签”，不宣称 AI。
- 若需要每日变化，内置 7–30 条审核文案，以 `userId + localDate` 稳定取模。
- 可选加入 mood 维度，但同一天刷新结果必须稳定。
- 不增加后端，除非明确要求跨设备历史一致或运营动态更新。

#### 日记完成反馈

- 当前同步生成流程：接口成功后刷新日记并显示 snackbar/inline 成功反馈。
- 不为同步请求增加伪异步通知。
- 若比赛脚本确实要求跨页面提醒，再增加带 `eventId` 和 `diaryDate` 的 `diary.ready`，以及 pending 查询和幂等 acknowledge。
- 本轮不做 FCM、系统通知和后台自动生成。

验收：

- 幸运签同账号同日期稳定，不依赖网络，不宣称 AI。
- 日记生成失败不显示“准备好了”。
- `DiaryReady` 跳转到事件指定日期，而不是无条件打开今天。

### I5：真机、发布和演示门禁

预计：2–4 人日  
优先级：P0，与 I1/I2 并行  
目标：把自动化能力变成可参赛证据。

任务：

- 至少一款 T8171/T8214：账号、设备列表、Live、裸帧、事件、缩略图、隐私控制和断流恢复。
- Android API 26+ 真机：登录、四 Tab、Camera start/stop、提醒、七种心情、日记和断网恢复。
- Web 产生提醒 → Android 认领响应 → 后端结果 → 日记的完整录屏。
- 连续运行至少 15 分钟，记录设备、固件、网络、时间和版本 SHA。
- 固定 Windows 包、Android APK、后端 SHA、校验值和安装说明。
- 完成两次 6 分钟排练和断网/设备离线/云端不可用预案。

发布门禁：

- 后端、Web、Android 和桌宠相关自动化通过；跳过项必须解释。
- 真机失败不以模拟或单元测试替代。
- 生产包不包含 LAN 桌宠 URL/token、模型 Key 或设备凭证。
- 不在 PPT 中把固定签文、示例媒体、聊天壳或 Eufy Live 预览描述成已完成智能能力。

## 6. 赛后产品化 Backlog

### B1：桌宠正式配对

预计：5–10 人日，不含证书/安装体系长期维护。

- 先确定 canonical Electron 或独立 LAN 桌宠为唯一正式路线。
- 桌面显示短时一次性配对码/QR 和 endpoint 指纹，双方人工确认。
- 握手后签发每设备凭据；桌面支持设备列表、撤销和轮换，Android 用 Keystore 保存。
- 使用可验证 TLS；若仅本机演示，优先 loopback/USB，不在明文 Wi-Fi 发送长期凭据。
- 增加连接状态、重试、撤销以及 show/hide；若承诺暂停，扩展命令协议。
- 验证重放、错误主机、token 泄露、断网和升级保留。

### B2：日记静态图片

预计：5–8 人日；视频另计。

- 只在用户明确点击时抓取一张真实帧。
- 优先保存在 Android app 私有目录或 MediaStore，用本地元数据按 eventId/date 关联。
- 日记 entry 可打开、删除；删号/清理时同步删除媒体。
- 单机方案不得把 `content://` URI 上传为跨端引用。
- 云端图片需要对象存储、授权 URL、元数据表、加密、留存和删除审计，另立设计。

### B3：日记视频

预计：10–20+ 人日。

需要前后缓冲、编码、体积限制、转码、播放、上传恢复、跨端授权、保留期限、删除和隐私同意。比赛前不实施。

### B4：Android 本地视觉感知

预计：8–15+ 人日，取决于 ESIotKit 是否提供稳定可分析帧。

- 先通过官方资料/真机确认连续解码帧 API 与许可边界，不能从 Surface 预览推断可取帧。
- 增加端侧 MediaPipe、帧抽样、背压、旋转、镜像、生命周期和温控降级。
- 移植并重新校准 Web 的 baseline、PostureWindow、StandingDetector、SedentaryTracker 和 BehaviorEngine。
- 建立 device/session/seq/eventId，接入 events、snapshots、samples 和离线队列。
- 原始帧和 landmarks 留在端侧；补知情同意、暂停和分类清除。
- 用真人标注集验证误报/漏报、角度、光照、遮挡、电量和 15 分钟稳定性。

## 7. 推荐排期

假设 2 名开发并行、先保比赛主链：

| 时间 | 开发 A | 开发 B | 联合验收 |
|---|---|---|---|
| Day 1 | I1 ReminderRepository/合同复核 | I2 未知 Mood 容错和后端七值 | I0 范围冻结 |
| Day 2 | pending/displayed/respond | Android 七值 UI/API/Schema | 单元与集成回归 |
| Day 3 | 双端竞争、恢复和账号隔离 | Eufy/Android 真机检查 | Web → Android 提醒闭环 |
| Day 4 | 可选 WebSocket 加速 | 幸运签/日记成功反馈 | 15 分钟稳定性 |
| Day 5 | 缺陷修复、固定包 | 缺陷修复、验收记录 | 第一次 6 分钟排练 |
| Day 6+ | 仅在核心全部通过后开始 Chat MVP | 发布物与备用视频 | 第二次排练和冻结 |

若剩余时间少于 5 天：只做 I1、I2、I5，不启动聊天、媒体、正式配对或 Android 视觉。

## 8. 风险与回退

| 风险 | 控制与回退 |
|---|---|
| WebSocket 丢消息或 scheduler 不 publish | HTTP pending polling 始终保留为可靠路径 |
| Web/Android 重复显示提醒 | 显示前调用 displayed，以服务端 2xx/409 决定归属 |
| 新心情导致旧 App 解析失败 | 先发布容错 reader，再开放七值 writer；必要时保持服务端四值开关 |
| Chat 模型不可用或超时 | 显示真实失败/模板降级；请求使用 clientMessageId 幂等 |
| Eufy 真机不稳定 | 切 Webcam 或明确标记的模拟回放，不伪造设备结果 |
| LAN 桌宠凭据暴露 | release 继续禁用；现场仅受控网络/loopback/USB，赛后实现正式配对 |
| 媒体引入隐私和体积风险 | 比赛前不做视频；图片也只在明确授权后单机保存 |
| Android 视觉拖慢主链 | 保持 Web/Electron 唯一感知端，Android 视觉进入赛后 Backlog |

## 9. 完成定义

本计划的比赛阶段只有同时满足以下条件才算完成：

1. Android 能可靠消费并响应真实后端提醒，断线可补查，多端不重复展示。
2. 七种心情可保存、回读、删除，未知未来值不会破坏同步。
3. 至少一条真实设备/真实账号主链有可追溯证据。
4. 隐私同意、暂停、清除和账号隔离没有回归。
5. 后端、Web、Android 自动化通过，所有跳过项有明确原因。
6. Windows 包、APK、后端 SHA、安装说明和备用视频已固定。
7. 两次 6 分钟计时排练完成，演示口径与实际能力一致。
