# LookBuddy 开发与同步约定

- 每次 LookBuddy 代码改动，必须同时更新本地工程与 `https://github.com/Janbecca/LookBuddy`，报告分支和完整提交号。仅本地修改或仅远端修改都不能称为已同步。
- 实际运行工程为 `D:/Everything/Anker/projects/lookbuddy`。工作区其他 LookBuddy 目录及 output 下副本仅作历史资料或临时工作文件，不能默认为最新代码。
- 对应远端目录为 `apps/android-local`，保留 `apps/android` 的独立实现，不混合两套代码。
- 同步分支为 `codex/android-local-sync`。PR #2 已于2026-10-16合并。main 另有后端集成更新，与此分支及 D 盘工程尚未合并，不能直接覆盖；本次素材改动仍同步约定分支，不代表 main 已更新。
- 修改前先读远端分支当前提交，逐文件核对本地与远端 Git blob SHA，保留双方改动；存在冲突时先处理，不能直接覆盖。
- 当前通过已授权 GitHub 连接器同步；本工程无 `.git`，不能假定命令行 pull/push 或后台自动同步可用。
- 验证通过后创建提交，使用期望分支 SHA 更新分支，防止覆盖并发改动；不强制推送。提交后逐文件比较 Git blob SHA，并更新工作区同步记录。
- 不提交 `.env`、API Key、`local.properties`、IDE 配置、构建缓存和本机构建输出。用户提供的字体原始压缩包保留本地，App 使用的字体资源随代码同步。
- 已安装的手机或模拟器 App 需要重新构建安装才会更新。复现首次注册从「个人设置 → 系统设置 → 重新体验初次见面」进入，不清除用户数据。
- 动画统一由 BuddyWebpOnce 解码 raw WebP；注册 Greeting/FormalIntroduction 使用 buddy_intro，其他注册页及开屏仍保留经过签名校验的 PNG。禁止 painterResource 读取动图或用改扩展名的 GIF 冒充 PNG。旧版注册状态（flow_version < 3）更新后重新展示完整流程一次，保留昵称及其他数据。后续动态替换集中修改 `OnboardingArtwork`，保留各页设计槽位。

- 日期/时间以 AppTime.kt 的统一设备本地时钟为准；固定时刻只允许用于预览和测试。工作时段通过 WorkScheduleStore 持久化，默认为每日09:00–18:00，支持跨夜，按结束时刻排除。18:00–06:00 独立使用夜间场景。
- 夜间素材来自 Figma 229:8168；home_room_night.png 是原始背景，home_buddy_night.png 是设计素材的首帧静态 PNG，不能改扩展名冒充 PNG。幸运签周心情区域不得恢复固定高度，需覆盖小屏大字体验证。
- 全 App 底部统一使用 CompanionNavigation 四入口：首页 / 陪伴视角 / 陪伴日记 / 个人设置。信号图标为摄像头页面，书本为 DiaryScreen；摄像头页独立选中 Camera。视觉以 Figma 303:4309 当前纯图标底栏为准，SVG 在 assets/navigation，禁止回退三 Tab 或将日记绑定信号图标。
- 首页办公动画由 LookBuddyApp 上层 WorkBuddyPlayback 调度：每次进入前台重播一遍，前台每3600000ms重播，末帧保持；切 Tab 不重置已完成状态。日记动画位于轮播外，页面每次进入仅播一遍，翻气泡不重播。心情选择为六张资产图，旧 Tired 记录保留。
