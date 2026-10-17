# Android lint 门禁修复与续轮验收

日期：2026-10-17。分支：`codex/frontend-feature-iteration`。
续轮基线：`ba387f2c3e027cc60c36eea7d052ae9714967a02`。

## 修改与原因

原验收入口把 `:app` 指向产品目录，再通过 buildFileName 指定 `integration.gradle.kts`。AGP 8.11.1 的 Kotlin UAST 在分析该构建脚本时发生 KaModule 崩溃，尚未检查到实际 App 兼容问题。Google 登记的相似脚本分析问题为 [Issue 430991549](https://issuetracker.google.com/issues/430991549)。本地采用下述入口后该崩溃消失，这是本项目的验证结果。

将验收模块改为标准 `integration/app/build.gradle.kts`。各 source set 显式引用原有 `app/src/{main,debug,release,test,androidTest,...}`，SDK AAR、字体、资源、网络配置及本地构建配置仍来自原产品目录；输出继续是 `integration-build/app`。没有创建第二份 Android 产品源码，也没有更换 AGP、Kotlin、SDK 或 JDK 版本。

正常 lint 随后发现基础主题把仅 API 27 支持的 windowLightNavigationBar 属性放在 API 26 的默认资源中。现提取公共基础主题，将该属性放到 `values-v27`；保留 API 31 的开屏主题及最低 API 26，未通过禁用检查或提高 minSdk 处理错误。

check.ps1 新增 GradlePath 参数，默认使用已存在的本地 Gradle 8.13；不存在时明确要求传入路径。它不再把面向原生 AGP9 的 Gradle9 wrapper 用于 AGP8 验收入口。PowerShell 语法解析通过，联调脚本 --help 也已更新。

心情 UI 测试曾在磁盘写入完成但 IO continuation 尚未调度翻转动画时断言，出现不稳定失败。改为等待实际 recorded_mood 语义节点出现，仍严格核对“好奇”保存结果，没有放宽产品成功标准。

## 验证结果

以下命令完整成功，并在结束后关闭临时后端与数据库：

```powershell
& 'apps/backend/.venv/Scripts/python.exe' scripts/product_integration.py `
  --gradle (Join-Path (Get-Location).Path '.git/codex-tooling/gradle-8.13/bin/gradle.bat') --lint
```

- assembleDebug 成功。
- testDebugUnitTest：51 项中 50 通过、1 跳过、0 失败；其中真实后端接口联调通过。跳过项仍是未配置独立 LAN 桌宠服务的协议集成，桌宠本机 Node 测试已在上一轮通过。
- lintDebug 成功：0 error、90 warning、1 hint。警告仍可在报告中查看，主要为依赖版本提示、图标位置/重复、KTX 与未使用资源；不将 lint 成功描述成零警告。
- 合并后的 Manifest 仍为 minSdk 26。
- 源码、字体、SDK 及资源路径均指向原产品目录。测试数量与首轮一致。
- 更新验收 APK，并把 HTML/XML/TXT lint 报告复制到 `output/frontend-iteration-20261017/lint/`，版本与校验值记录在 manifest.json。

## 使用入口

```powershell
# 全项目检查；可用本地 Gradle 8.13 时无需显式参数。
./scripts/check.ps1 -GradlePath 'C:/path/to/gradle-8.13/bin/gradle.bat'

# 仅 Android 构建、单测及 lint。
& 'C:/path/to/gradle-8.13/bin/gradle.bat' -p apps/android-local/integration `
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

## 仍需设备与现场验证

没有连接 Android 真机，不将 Robolectric 或临时后端联调当成真机验收。仍需至少一款 T8171/T8214，核验真实账号、Live、媒体、提醒响应、网络/后台恢复与账号切换；完成连续 15 分钟稳定性、真实端到端录屏、云端实际 SHA 核对、生产构建审查及两次 6 分钟排练。

真实聊天继续遵守原计划停止条件，尚未启动；用户随后已明确授权将本轮改动整合并推送到 main，不执行生产部署。

## 同步状态

本轮 9 个文件的改动（含构建脚本移到标准模块位置）已完成本地验证。用户随后明确要求“你推吧”及“整合一下推到 main 里”，授权把 lint 修复与前端功能一并整合到 Janbecca/LookBuddy 的 main 分支。同步前核对远端 main 基线，使用期望分支 SHA 更新，逐文件验证 Git blob 与旧路径删除；最终提交号及同步状态见验收产物目录的 manifest.json。
