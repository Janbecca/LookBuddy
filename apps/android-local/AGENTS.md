# LookBuddy 开发与同步约定

- 每次 LookBuddy 代码改动，必须同时更新本地工程与 `https://github.com/Janbecca/LookBuddy`，报告分支和完整提交号。仅本地修改或仅远端修改都不能称为已同步。
- 实际运行工程为 `D:/Everything/Anker/projects/lookbuddy`。工作区其他 LookBuddy 目录及 output 下副本仅作历史资料或临时工作文件，不能默认为最新代码。
- 对应远端目录为 `apps/android-local`，保留 `apps/android` 的独立实现，不混合两套代码。
- 同步分支为 `codex/android-local-sync`。PR #2 已于2026-10-16合并。main 的 Android 集成更新尚未合并到 D 盘 App，不能直接覆盖；后端已按下述记录单独合入，本地变更仍同步约定分支，不代表 main 已更新。
- 修改前先读远端分支当前提交，逐文件核对本地与远端 Git blob SHA，保留双方改动；存在冲突时先处理，不能直接覆盖。
- 当前通过已授权 GitHub 连接器同步；本工程无 `.git`，不能假定命令行 pull/push 或后台自动同步可用。
- 验证通过后创建提交，使用期望分支 SHA 更新分支，防止覆盖并发改动；不强制推送。提交后逐文件比较 Git blob SHA，并更新工作区同步记录。
- 不提交 `.env`、API Key、`local.properties`、IDE 配置、构建缓存和本机构建输出。用户提供的字体原始压缩包保留本地，App 使用的字体资源随代码同步。
- 已安装的手机或模拟器 App 需要重新构建安装才会更新。复现首次注册从「个人设置 → 系统设置 → 重新体验初次见面」进入，不清除用户数据。
- 动画统一由 BuddyWebpOnce 解码 raw WebP；注册 Greeting/FormalIntroduction 使用 buddy_intro，其他注册页及开屏仍保留经过签名校验的 PNG。禁止 painterResource 读取动图或用改扩展名的 GIF 冒充 PNG。旧版注册状态（flow_version < 3）更新后重新展示完整流程一次，保留昵称及其他数据。后续动态替换集中修改 `OnboardingArtwork`，保留各页设计槽位。

- 日期/时间以 AppTime.kt 的统一设备本地时钟为准；固定时刻只允许用于预览和测试。工作时段通过 WorkScheduleStore 持久化，默认为每日09:00–18:00，支持跨夜，按结束时刻排除。18:00–06:00 独立使用夜间场景。
- 夜间素材来自 Figma 229:8168；home_room_night.png 是原始背景，home_buddy_night.png 是设计素材的首帧静态 PNG，不能改扩展名冒充 PNG。幸运签周心情区域不得恢复固定高度，需覆盖小屏大字体验证。
- 1.5 起按用户最新要求删除摄像头页面、顶部摄像头入口及信号 Tab；CompanionNavigation 只保留首页 / 陪伴日记 / 个人设置三个纯图标入口，书本绑定 DiaryScreen。不得重新添加摄像头页。
- 首页办公动画由 LookBuddyApp 上层 WorkBuddyPlayback 调度：每次进入前台重播一遍，前台每3600000ms重播，末帧保持；切 Tab 不重置已完成状态。日记动画位于轮播外，页面每次进入仅播一遍，翻气泡不重播。心情选择为六张资产图，旧 Tired 记录保留。

- 1.5 首页提醒以 Figma 315:4093 为准；“知道了”必须在近圆形48%白色气泡内部。HomeReminderController 在 Tab 上层保存状态，HomeReminderSource 是未来后端入口；已确认ID去重，动画结束不自动关闭，统计面板展开暂时隐藏浮层。缺少的姿态/起身/开心/担忧完整动作暂为明确记录的占位，后续统一替换 HomeReminder.kt 中 ReminderArtwork。

- 2026-10-16 后端合并：实际 backend 以 main@69622817b38c1ebdf7af846cd8fab9e9826f164f 的 apps/backend 为主，完整导入38个上游文件并保持业务源码与测试原样；本地启动兼容 python app.py/start.bat、5210端口及旧GLM_*环境变量。合并结果对应远端 apps/android-local/backend，旧5文件保存在本地 backend-backups（不提交）；.env/数据库/虚拟环境不提交。GitHub问候接口要求Bearer登录，旧App无令牌请求仍需后续接入；本次没有修改Android页面或绕过鉴权。86项测试和隔离HTTP验证通过。后端后续修改 src/lookbuddy；继续按约定分支同步，具体提交号以工作区同步记录为准。

2026-10-16 Buddy统一语气规则：backend/src/lookbuddy/buddy_prompt.py 封装人格、主动提示、聊天与每次输出前的语病/人格自检。仅主动提示最多30字符（含标点）；聊天无固定字数上限，口语自然、引导倾诉、理解后给建议。仅疲劳、开心表情、可能不轻松表情、晚间问候提供chatEntry聊一聊入口，其他场景为null。事件提醒沿用固定话术，动态问候使用共享Prompt，超长或异常输出回退完整短句，稍后提醒保留表情入口。前端按钮和聊天API尚未实现。本地backend对应约定分支apps/android-local/backend，不更新main或apps/backend。来源基线1dd748503431ab2f29526736592e737cd3cf3556；本次提交号与逐文件核对结果见工作区output/buddy-prompt-20261016/sync-result.json，后续以实际分支最新提交为准。

- 2026-10-16 App 1.6.0 聊一聊前端：按Figma 299:3996新增暖色背景聊天页，日记页354:4094气泡及首页入口可进入，疲劳/开心/关心提醒按钮进入聊天，其他三类为“好的”并关闭。复用现有三Tab；返回恢复来源页面，草稿与未发送内容支持Activity状态恢复。聊天API未接入，用户内容明确标记暂未发送，不模拟Buddy回复。原始Figma素材本地打包；仅前端改动，同步apps/android-local约定分支，不更新main或apps/backend。本次提交号和验证结果见工作区output/lookbuddy-chat-ui/sync-result.json。具体范围见CHAT_UI.md。

- 2026-10-16 App 1.6.1 首页动画纠正：按用户给定路径接入饮水/伸展/坐姿/趴桌犯困/开心/安慰倾听/早间七个原始WebP，文件Git blob SHA逐一登记于HOME_REMINDER_ASSETS.json。删除首页顶部单独聊一聊入口，首页仅疲劳/开心/关心气泡进入聊天，日记页入口保留。默认态尺寸及播放逻辑不改；其他动画按现有饮水有效高度192.71484dp统一等比缩放，宽场景保持屏内，坐姿素材的黑色边带仅在渲染时裁开，原始文件不改。早晚问候无按钮，工位日记去看看跳到日记；后端触发接收仍待接入。本次同步记录见工作区output/lookbuddy-animation-fix/sync-result.json。

- 2026-10-16 桌宠0.2场景统一：正式工程为桌面宠物/LookBuddy_Desktop；饮水/久坐/坐姿/疲劳/开心/关心/早间七种动作复用App首页原始WebP，manifest登记Git blob SHA。晚间保留看电视吃薯片，diary_ready保持当前日夜默认态，新增接收约定但后端自动生产尚未接入。Android首页局域网桥接转发当前提醒种类、事件ID和默认场景；提醒结束恢复默认，同ID不重播，新ID重播，播完保持末帧。22项桌宠单元/HTTP测试、7种真实Electron场景截图及末帧/去重测试通过；Android52项测试与调试包构建通过。原有完整窗口回归在本机出现464×460尺寸差异与置顶恢复失败，未声称完整桌宠回归通过。未安装手机App。具体同步提交及核对范围见工作区output/desktop-scene-sync/sync-result.json；不更新main/apps/android/apps/desktop/apps/backend，保留旧desktop及根README差异。实际D盘工程仍无.git；本次使用工作区隔离Git仓库读取最新分支并尝试普通提交推送，不代表后台同步。

- 2026-10-16 App 1.6.2：首页IP和气泡整体适度下移，最大32dp并按陪伴标语/统计面板剩余空间限制位移，默认态大小不变；所有首页提醒/问候/日记提醒气泡删除主标题，仅保留15sp、CompanionMuted色的提示正文及原按钮。同步记录见工作区output/lookbuddy-home-spacing/sync-result.json。

- 2026-10-16 App 1.6.3 首页提醒气泡重排：正文采用深色15sp/24sp行高并增加背景对比度，取消固定155dp高度，30dp圆角内容自适应卡片；按钮视觉收为80dp宽、约28dp高的小胶囊，仍保留至少44dp点击区域。保留无标题、原按钮动作及IP位置/尺寸。同步记录见工作区output/lookbuddy-bubble-layout/sync-result.json。

- 2026-10-16 桌宠0.3：首页镜像与尺寸减半。已有467 DIP尺寸迁移为233 DIP，只迁移一次并保留右下角位置；七种动作共用窗口等比缩放，原生边框允许像素取整差异。气泡只显示首页实际提示，无提示/确认关闭/首页面板暂隐时明确清空，不再显示动作名称、连接问候或残留文本。Android转发HomeReminderController状态及完成态，2秒心跳、变更即时同步，删除不可见的独立问候对桌宠的影响；办公/晚间默认素材亦与首页原字节一致。24项桌宠测试及7种Electron场景、统一尺寸和提示清空验收通过；Android52项测试及APK构建通过，-r更新当前手机保留数据。当前真机通过USB adb reverse5211和本机127.0.0.1配置与桌宠实测连接，默认夜间状态及空提示已验证；手机拒绝ADB注入点击，未修改安全设置，七种切换在隔离Electron中验证。local.properties/配对令牌不提交。实际桌宠已更新运行。原完整窗口DPI/置顶回归限制仍保留，不宣称通过。同步分支仍codex/android-local-sync，提交及逐文件结果见工作区output/desktop-home-sync/sync-result.json；不更新main/apps/android/apps/desktop/apps/backend。

- 2026-10-16 桌宠气泡间距微调：仅将桌面气泡在460px设计坐标内下移32px（当前半尺寸窗口约16 DIP），贴近Buddy；使用transform保持气泡槽位、宠物位置与尺寸及窗口尺寸不变，提示为空仍不显示气泡。完成桌宠构建与7场景视觉核对；本次未改Android、后端或手机安装。提交与逐文件核对记录见工作区output/desktop-bubble-gap/sync-result.json。
