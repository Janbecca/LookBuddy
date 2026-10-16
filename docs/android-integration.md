# Android 产品前后端整合

本轮产品客户端是 `apps/android-local`，后端是 `apps/backend`。`apps/android` 仍为旧调试客户端；产品页不再依赖单独的5210服务。部署由用户执行。

## 已接通的范围

- 演示手机号登录、会话续期、昵称/账号时区、工作计划、退出和删号。
- 计划上下班与实际陪伴分开；当前会话、开始/结束、30秒前台心跳和失联截止计时。
- 首页今日/月度工作陪伴时长、饮水/伸展/休息事实统计、陪伴天数和最新姿态；没有证据时显示待观察，不把会话叫专注时长。
- 每日心情自评四类，可按日期更新/查询/删除；不改原始表情观察和纠正，不写学习奖励。
- 关心语使用统一后端，保留 GLM 标准接口、show/message/source、重复抑制和模板降级。没有真实天气时不编造天气。
- 日记按真实日期读取时间线，生成/编辑/删除正文；重新生成保留用户编辑。产品账号不会显示设计稿示例历史。
- Mark 只上传幂等事件，进入日记；离线保存待补传元数据。本轮没有录像、没有前后5秒缓存、不上传本地视频 URI。
- 官方 ESIotKit 2.2.7 AAR 原生 LiveMediaPlayer Adapter，设置中临时填写官方设备配置，选择首个在线设备。真实播放、断线恢复需真机验收，未实现视觉识别或录像。
- 提醒与动作闭环不接产品 UI，原有后端和调试客户端能力保留。

## 演示登录边界

这不是手机号验证。输入手机号直接取得演示账号；页面始终标注“演示手机号 · 未验证”。同一安装 ID + 手机号恢复同一账号，另一台设备/重装不会仅凭手机号取得该账号，不合并现有邮箱账号。

服务端必须显式配置 `LOOKBUDDY_DEMO_LOGIN_ENABLED=true`。只允许 development/competition，production 无条件拒绝。旧邮箱登录仍保留。正式发布前必须替换为真实身份验证。

## 后端启动配置

本地从 `apps/backend` 启动，使用项目 Python 环境安装依赖后：

```powershell
$env:LOOKBUDDY_DEMO_LOGIN_ENABLED='true'
python -m uvicorn lookbuddy.main:app --app-dir src --host 0.0.0.0 --port 8080
```

本地默认 SQLite。云端沿用 `deploy/launch/` 的 competition 配置和持久化 `/data`，额外开启上述演示开关；JWT、HTTPS origin、备份配置按既有部署文档设置。不在仓库填写真实凭证。

GLM 可选后端配置：`LOOKBUDDY_GREETING_BASE_URL`（标准接口根地址，代码追加 `/chat/completions`）、`LOOKBUDDY_GREETING_API_KEY`、`LOOKBUDDY_GREETING_MODEL`。未配或不可用时返回模板。App 不保存模型 Key。

App 顶部“后端设置”填写后端 origin，不加 `/v1`。模拟器默认 `http://10.0.2.2:8080`；真机本地调试填电脑局域网 IP，外网使用 HTTPS。Debug 仅允许私网/回环 HTTP，Release 只接受 HTTPS。切换后端会清除当前登录，避免串账号。

## 接口（以 OpenAPI 为准）

| 功能 | 接口 |
|---|---|
| 演示登录 | `POST /v1/auth/demo-phone`，phone/installationId/displayName |
| 资料 | `GET /v1/profile`、`PATCH /v1/me` |
| 工作计划 | `GET/PATCH /v1/work-schedule`，start/end，HH:mm |
| 实际会话 | `GET /v1/sessions/current`、`POST /v1/work/start`、`POST /v1/work/heartbeat`、`POST /v1/sessions/{id}/finish` |
| 首页/最新状态 | `GET /v1/home/summary?day=YYYY-MM-DD`、`GET /v1/state/latest` |
| 自评 | `GET /v1/moods?start=...&end=...`、`GET/PUT/DELETE /v1/moods/{day}` |
| 关心语 | `POST /v1/buddy/greeting`，兼容 `/buddy/greeting` |
| 日记 | `GET /v1/diary-page/{day}`，原 `/v1/diaries/{day}` 读改删及 `/generate` |
| 标记 | `POST /v1/marks`，eventId/occurredAt/note，无视频地址 |

除演示登录外均复用 Bearer 身份。新表为增量创建，不修改旧邮箱账号数据。删除账号级联删除新资料/自评/租约/演示身份；Mark 属于原事件表。备份仍用 SQLite 一致性备份工具，不直接复制运行中的 DB。

## Android 构建与验证

队友原工程要求 SDK37、Gradle9.6、JDK25。当前机器缺 SDK37，因此添加独立入口，保留同一套产品源码/UI资源，使用 SDK36、Gradle8.13、AGP8.11.1、JDK17 和相同 Compose BOM；最低 API26 未下调：

```powershell
gradle -p apps/android-local/integration :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

APK：`apps/android-local/integration-build/app/outputs/apk/debug/app-debug.apk`。构建目录不提交 Git。

真实客户端→临时 FastAPI→隔离 SQLite 全链路测试（不是 HTTP mock）：

```powershell
python scripts/product_integration.py --gradle C:\path\to\gradle-8.13\bin\gradle.bat
```

脚本创建临时数据库并启用演示登录，自动选择本机端口，执行产品测试/打包，最后停止其自己启动的服务。可加 `--lint` 单独尝试静态检查。未设置测试服务地址时，单独跑 Gradle 会跳过该条联网测试，不能据此称为联调通过。

后端回归：在 `apps/backend` 配置 `PYTHONPATH=src` 后执行 `python -m pytest`。测试覆盖演示开关、隔离身份、心情、会话租约、统计去重、Mark 幂等、日记编辑保护及旧表情纠正。

## 待真机验证

2026-10-16 验证记录：后端 `pytest -q` 77项通过（1条 Starlette/httpx 弃用警告）；产品 APK `assembleDebug` 成功，Android33项测试全部通过、0跳过，包括真实隔离后端的 access token 续期、日记编辑保护及删号。临时服务已关闭。整合基线已再次核对，队友分支仍为 `61cebdfd9418cce6a90696b0c9ac86a06cd8d758`。

本机静态检查的限制：AGP8.11.1 lint 在分析自定义 Gradle 脚本时崩溃（`Cannot find a KaModule for the VirtualFile`）；未将其静默忽略，也不宣称 lint 通过。APK编译/打包和33项 JVM/UI/真实网络测试独立执行。

没有官方设备凭证/在线真机，本轮不宣称 SDK 真机播放成功。没有配置真实 GLM 服务，本轮验证降级分支，不宣称模型线上调用通过。未替用户部署、安装或启动真机 App。

APK 与旧调试客户端使用相同 applicationId；安装时应确认签名是否一致，不要为解决签名冲突直接清空旧数据。设备安装更新由用户决定。
