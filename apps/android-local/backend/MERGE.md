# 后端下载与合并记录

- 日期：2026-10-16。
- 上游：`https://github.com/Janbecca/LookBuddy/tree/69622817b38c1ebdf7af846cd8fab9e9826f164f/apps/backend`。
- 同步前分支：`codex/android-local-sync`，提交 `2c63eda2e97a0e94053fadc891760694601c2768`。
- 实际本地目标：`D:/Everything/Anker/projects/lookbuddy/backend`。
- 同步目标：`apps/android-local/backend`。仓库原有 `apps/backend` 和 `apps/android` 不在本次改动范围。

38 个上游文件完整下载，并在落盘时核对 Git blob SHA；核心业务和上游测试保持原样。
原本地的 5 个文件另存到工程根 `backend-backups` 下的带时间目录。
原文件与同步前 `apps/android-local/backend` 的 5 个 Git blob SHA 全部一致。

合并只替换旧的独立问候服务入口，保留 `python app.py`、`start.bat`、5210 联调端口，
增加 `GLM_*` 到 `LOOKBUDDY_GREETING_*` 的兼容映射。
保留既有 `.env`、数据库及其他本地未跟踪文件；不上传配置密钥、备份、缓存或数据库。
问候鉴权、无真实天气时不推送雨天提醒、五类提醒和日记规则均以上游为准。

Android 界面、桌宠和其他本地未同步的文件没有纳入这次后端改动。
本次不部署、不自动替换已运行的服务、不安装手机 App。

验证：86项测试通过；使用临时数据库真实HTTP验证启动、健康检查、鉴权、问候、日记读写通过。
