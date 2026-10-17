# 前端迭代交付与验收

日期：2026-10-17。分支：`codex/frontend-feature-iteration`。
基线：`4002a8b8532b49255855a0dd910c0c515d93da45`，与读取时 GitHub main 相同。

## 已实施

- Android 生产入口自动创建账号及连接代际作用域的 ReminderRepository。前台立即查 pending，之后每 12 秒补查；恢复前台与网络重连触发补查，进入后台取消轮询。保留 HTTP 作为可靠路径，未增加 WebSocket 或系统推送。
- 先 displayed 认领，再展示稳定服务端 ID 的提醒。饮水、疲劳、姿态、久坐映射到原首页卡片；接受、延后 10 分钟、忽略均请求真实服务端，失败保留卡片与重试入口，提交中禁用按钮。
- 修复已有后端 displayed 幂等不等于独占的问题：新增 DisplayClaimIn.claimId，账号事务锁内确定唯一展示端。同端带相同 claimId 可重试，其他端 409；响应接口验证认领归属。Web 同时更新 claimId 协议。旧版无请求体仅可完成首次认领，重试返回 409，应与新 Web/Android 一起发布。
- Android 认领标识来自安装实例，重启后可恢复自己已认领但未响应的 pending。更换账号/连接代际后旧请求通过 expectedIdentity 校验停止；页面、提醒及聊天草稿内存按账号隔离。
- 兼容 SQLite 历史时间无时区后缀与带 Z 的 UTC 时间。此问题由真实 Android/后端联调发现并修复，不能只靠带 Z 的模拟数据验收。
- Mood 扩展为 Happy / Calm / Tired / Sad / Curious / Thoughtful / Excited。后端、Android、共享合同一致；未知未来值及错误日期逐行跳过，避免整个 sync 失败。七种心情可保存、回读和删除，不进入表情观察或奖励。
- 暖心签仍为固定文案，增加“固定暖心签”说明；没有新增 AI/每日变化承诺。同步生成日记成功且刷新成功后显示指定日期的完成反馈；失败不显示成功。可注入 DiaryReady 支持指定日期跳转，未接入虚构的后台通知源。

## 并发改动与同步边界

实施前等待「确认后端图片上传隐私约定」停止运行，并保留它写入的设备媒体引用、历史读取及播放代码。ProductBackend 与 DiaryScreen 的现有媒体调用依赖相应 Android 文件、后端模型、路由及迁移；这组已有实现作为构建依赖一并保留。设备媒体仍需真机确认，不将其算作本轮已验证的新能力。

工作目录使用当前 main 对应的 `E:/MyProjects/Anker 黑客松/LookBuddy-main`。Android AGENTS 中的 `D:/Everything/Anker/projects/lookbuddy` 在本机不存在，旧同步分支早于整合 main，故采用新分支，不移动旧同步分支或覆盖 main。

部署、数据库连接配置及其他文档整理的既有未提交改动留在工作区，不混入本次功能同步。

首轮 34 个源码及文档文件已同步到 Janbecca/LookBuddy 的 `codex/frontend-feature-iteration` 分支，并逐文件校验 Git blob SHA。随后用户明确要求“整合一下推到 main 里”，因此把本轮功能与后续 lint 修复一并整合到 main，通过期望分支 SHA 防止覆盖并发提交。最终提交号与校验结果记录在验收产物目录的 manifest.json 中；不执行生产部署。

## 验证证据

| 检查 | 结果 |
|---|---|
| 完整后端 pytest | 119 通过；包含七值保存/回读/删除、两个客户端同时认领仅一端成功、同端重试、响应归属、持久化延后、媒体与原有隐私回归 |
| 最终认领合同回归 | 12 通过 |
| Android 产品集成入口 | assembleDebug、testDebugUnitTest 通过；51 项中 50 通过、1 跳过 |
| Android 真实后端联调 | 通过 scripts/product_integration.py：七种心情真实回读，pending → displayed → accepted 后 pending 清空；临时数据库与服务在结束后清理 |
| Android UI/Repository | 覆盖真实响应失败保留卡片及重试、409 不显示、轮询恢复、未知心情兼容；Robolectric 不代表真机 |
| Android 跳过项 | 独立 LAN 桌宠协议集成未配置服务地址；其本机 Node 协议单测另行通过 |
| Web | 28 通过、1 跳过，TypeScript/Vite 构建通过；跳过既有 expression replay 集成，未配置相应真实后端 |
| canonical Electron 容器 | 6 项通过 |
| 新 Windows portable 包 | --smoke 退出码 0，isolated renderers/assets/model initialization/state sync/commands/权限及隐藏恢复通过；realCameraUsed=false |
| 独立 LAN 桌宠 | 24 项 Node 测试通过，TypeScript/Vite 构建通过 |
| Android lint | 初次执行遇到 KaModule 构建脚本分析崩溃；后续已修复并通过，详见 [续轮验收记录](17-android-lint-gate.md) |
| 真机 | adb devices 无设备，未完成 Eufy/Android 实播与现场验收 |

## 验收产物与剩余门禁

本机验收产物在 `output/frontend-iteration-20261017/`：debug APK、重新构建 Web 的 Windows portable 包、校验值、版本说明。debug APK 用于验收，不视为正式生产包；Windows 包为 Web/Electron 感知端容器，独立 LAN 桌宠继续仅作 debug/demo。

仍需完成：连接至少一款 T8171/T8214 与 Android API 26+ 真机；核对同账号、网络恢复、后台恢复、账号切换及真实媒体；录制 Web 感知 → Android 提醒响应 → 后端结果 → 日记；连续 15 分钟稳定性；验证生产构建与云端实际 SHA；两次 6 分钟排练和离线预案。

I3 真实聊天未启动，遵守原计划停止条件。正式桌宠配对、Android 本地视觉、FCM 与后台自动日记继续不在本轮实现范围。当前不能宣称比赛发布门禁全部通过。
