# Launch 联调部署

构建上下文必须为仓库根目录，Dockerfile 选择 `deploy/launch/Dockerfile`。
该镜像同时提供 Web 和 FastAPI，Android 使用相同 HTTPS origin。

| 平台设置 | 值 |
|---|---|
| 容器端口 | 8080 |
| 实例数 | 1 |
| 持久卷 | 10 GiB，挂载 `/data` |
| 卷所有者 / fsGroup | UID/GID 10001 |
| 存活检查 | `/health/live` |
| 就绪检查 | `/health/ready` |

环境变量：

```text
LOOKBUDDY_ENV=competition
LOOKBUDDY_DATABASE_URL=sqlite:////data/lookbuddy.db
LOOKBUDDY_CORS_ORIGINS=https://你的Launch应用域名
LOOKBUDDY_SCHEDULER_ENABLED=true
```

`LOOKBUDDY_JWT_SECRET` 在平台的运行时配置中生成至少 32 字符的随机值；不提交到源码。
重发必须保留相同值，避免已有账号的登录会话失效。
`LOOKBUDDY_CORS_ORIGINS` 支持逗号分隔的 HTTPS origin 或 JSON 数组。
挂载目录不可写时启动直接失败，请调整卷 UID/GID，保持非 root 运行。

部署后打开 HTTPS 根地址登录；Android 后端地址设置为同一 HTTPS origin。
WebSocket 路径 `/v1/stream` 必须支持 Upgrade 和 Authorization 请求头。
Web 的模拟闭环无需 eufy 凭证；官方 eufy Web OpenAPI 代理尚属后续设备联调范围。

## 验收

1. `/health/live`、`/health/ready` 均返回 200，根地址显示 Web 页面。
2. 使用测试账号登录，修改偏好，运行 12 种 demo 回放并查看奖励。
3. Android 登录同一账号，核对偏好；接受同一提醒两次应幂等，冲突响应返回 409。
4. 保留卷并重启 / 重新发布，登录同一账号确认偏好、日记和记录仍存在。
5. 运行备份并下载，使用本地 SQLite 打开核对 `PRAGMA integrity_check`。

本地预验收：`apps/backend/.venv/Scripts/python.exe scripts/launch_integration.py`。
Gradle 无法联网下载时，追加 `--gradle <已经校验的本地gradle.bat路径>`。
本地结果涵盖进程重启、持久化、Web HTTP 回放和 Android Kotlin HTTP 客户端，
云端卷保留、平台鉴权、HTTPS/WebSocket 和手机 UI 必须使用真实 Launch 地址继续验收。

## 一致性备份

在容器终端执行（每次使用新文件名）：

```sh
python tools/backup_sqlite.py --output /data/lookbuddy-backup-YYYYMMDD-HHMM.db
```

脚本使用 SQLite Backup API，包含已提交 WAL 记录，校验完整性并输出 SHA256；已有目标拒绝覆盖。
从平台下载该备份到本地并核对 SHA256。未下载前不要删除应用或卷。
恢复时停止服务，将已校验备份上传到**新卷或新数据库路径**后再切换配置；保留原卷用于回退。
Launch 具体下载和卷配置入口以控制台为准，当前仓库未绑定任何 Launch 应用。
