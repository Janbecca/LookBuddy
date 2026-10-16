# Android 前端与桌宠融合交付记录

日期：2026-10-17。代码融合及自动核查完成，完整真机人工验收待办。用户确认本轮效果并明确要求推送到 main；本次提交交付融合代码和记录，不执行部署。

工作分支：`codex/main-android-local-sync-ui`。
基线 main：`e1690bad36b6b1a88b6253f9954d6ab7c27eac57`。
来源 sync：`6fe273c82fc67d437937634e22e671931212490e`。
分阶段实现、异常和验证细节见 [完整进度记录](13-main-android-local-sync-ui-integration.md)。

## 已交付范围

| 范围 | 最终行为 |
|---|---|
| Home | 新视觉、Buddy 动画、明确标注的提醒预览；统计及开始/结束工作仍走 main 真实接口 |
| Diary | 新轮播、聊天入口和时间线样式；保留真实读取、生成、编辑、删除及失败提示；示例须主动进入 |
| Mood | 图片与三列布局；Happy/Calm/Tired/Sad 真实保存；其他三项明确未接入且不保存 |
| Settings / Onboarding / Camera | 保留账号、昵称、时段、退出、删除账号、Eufy 和相机能力；登录门禁及引导静态图不变 |
| 导航与聊天 | 四入口保留；聊天可打开、返回及恢复草稿，保持未连接/未发送状态 |
| 桌宠 | 完整导入来源前端；debug Android 可配置本地同步；离开 Home 或生命周期停止时暂停同步 |
| 主线保护 | 原有 21 处本地改动和 66 个受保护文件哈希一致；后端、数据库、部署及既有 apps/desktop 未被覆盖 |

桌宠配置及运行方法见 [DESKTOP_PET_SYNC.md](../../apps/android-local/DESKTOP_PET_SYNC.md)。默认不连接桌宠，release 不含开发 URL/token，也未放开明文网络。桌宠生产配对、TLS、控制页、安装包、签名、自动更新均不在本轮实现范围。

## 验证证据

| 检查 | 结果与边界 |
|---|---|
| Android 集成入口 | debug APK 构建成功；11 套共 48 项测试通过，0 失败/错误/跳过 |
| 真实服务测试 | 临时 FastAPI/SQLite 与实际 Node 桌宠服务；账号/刷新/工作/心情/日记及同步协议通过，临时服务已清理 |
| 桌宠单元与构建 | 24 项 Node 测试通过；TypeScript/Vite 构建通过 |
| Electron 场景与窗口 | scene smoke 和完整 window smoke 通过；覆盖拖动、缩放、置顶不抢焦点、隐藏/恢复、暂停、菜单动作、末帧等自动检查 |
| 来源及发布隔离 | 桌宠 66 文件与冻结来源逐字节一致；release 配置为空、cleartext=false；构建产物及本地配置被 Git 忽略，暂存区为空 |
| diff 格式 | 本轮 Android 与报告通过；仓库整体另有既存计划文档末尾空格，未改动 |

Android 使用现有 `scripts/product_integration.py`，配合隔离桌宠服务（具体步骤见进度记录 P5-P7）。本机可用入口为 `.git/codex-tooling/gradle-8.13/bin/gradle.bat`，SDK36/JDK17。默认 wrapper 与现有 AGP 不兼容；原生 SDK37/JDK25 环境未具备，未更改版本来掩盖限制。

桌宠复验在 `apps/android-local/桌面宠物/LookBuddy_Desktop` 执行：

```powershell
npm.cmd run check:scenes
npm.cmd run check
```

APK：`apps/android-local/integration-build/app/outputs/apk/debug/app-debug.apk`（约 308 MB，未提交）。Android XML 报告在 `integration-build/app/test-results/testDebugUnitTest`。桌宠 `artifacts/scene-smoke.json`、`artifacts/smoke.json` 及场景截图为本地忽略产物。

## 尚未验收

- 真机完整走一遍冷启动恢复、登录失败、刷新、退出、四入口切换及 Camera 离页释放。
- 真机检查首页视觉/动画/小时重播、真实工作统计、四种心情保存、三种未接入提示。
- 真机检查日记 CRUD、失败草稿、聊天键盘/返回、昵称/时段/删除账号、断网无假成功及 Eufy 硬件。
- API26/27 验证末帧兜底，API28+ 验证 WebP 单次播放；保留基线 minSdk26，不宣称 API24/25 支持。
- 模拟器 `10.0.2.2`、USB `adb reverse`、同 Wi-Fi 联动及关闭桌宠后的云端使用；协议 JVM/本机测试不替代这些设备实测。
- 手工操作系统托盘及退出、不同屏幕/字体/键盘和视觉对照；自动菜单回调检查不替代人工验收。
- lint 曾因 KaModule 工具链错误失败，未通过；未完成截图像素对照、签名生产包及安装器验证。P8 首次 ADB 枚举未能连接 daemon；随后在批准的本机读取权限下再次执行 `adb devices -l`，命令成功但设备列表为空，当前没有可供验收的已连接手机或模拟器。

真实提醒与 diary_ready 推送源、AI 聊天和新增心情后端仍未连接。上述项目与真机验收需要后续单独完成，当前不能宣称全部生产能力已就绪。

## 用户确认后的发布范围

- 用户确认本轮效果并授权推送 main，替代原计划中暂不提交/推送的限制。
- 提交仅包含本轮 Android/桌宠融合代码、资源、测试、使用说明与两份进度/交付记录；既存后端、部署、契约、wrapper 和其他文档修改留在工作区。
- 提交前已 fetch origin main，远端基线仍为上述 e1690ba；以正常快进方式推送，不强推。
- 用户反馈的旧首页截图包含旧版独立的“开始实际陪伴”按钮。随后检查融合 APK 包含新版“预览”和工作切换区代码，重新执行 assembleDebug 成功（任务为 up-to-date），另提供唯一命名的本地 APK。文件名更换没有改变包内容，也没有证明旧截图的具体安装原因。用户之后确认本轮效果可用。
- 本机桌宠已按用户要求启动，进程窗口存在且 /health 返回 200。它为独立 Electron 应用；当前交付 APK 的桌宠连接配置为空，不能宣称手机与桌宠已配对。
- APK、本机配置、构建产物和偏好不进入提交；远端提交号与推送结果以最终 Git 核验为准。
- 暂存审计补充：Git 对 15 个桌宠文本文件执行了正常 CRLF/LF 归一化，无语义差异，其余导入文件保持来源 blob。来源 style.css 自带 8 行 CRCRLF，原样保留，因此完整暂存 diff 的空白检查会报告该文件；其余本次暂存路径的空白检查通过。
