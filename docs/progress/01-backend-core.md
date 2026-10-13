# 第 1 步：基础架构与后端核心

状态：已完成并通过自动化测试  
日期：2026-10-04

## 目标

- 建立完全独立于 `LookBuddy-xyh` 的新工程。
- 固化账号、设备、会话、领域事件、状态快照、提醒和日记的数据边界。
- 在没有 SDK 和 Router 凭证时也能端到端运行。

## 已实现

### 工程

- 新目录 `LookBuddy-main`，未创建嵌套 Git，未修改 `LookBuddy-xyh`。
- Python 3.11 + FastAPI + SQLAlchemy 2。
- SQLite 开发数据库，模型保持 PostgreSQL 可迁移性。
- `.env.example` 中集中管理数据库、JWT、CORS 和 Router 配置。

### 账号与数据隔离

- 注册、登录、刷新令牌和退出。
- Argon2 密码哈希。
- 短期 JWT access token 与可撤销 refresh token。
- 所有设备、会话、事件、提醒和日记查询均按当前用户限定。
- 删除账号与删除日记接口。

### 事件与会话

- 设备绑定和工作会话。
- `lookbuddy.event.v1` 批量上报。
- 按 `eventId` 及 `(deviceId, seq)` 幂等去重。
- 事件时间线查询。
- 视频和关键点不属于后端输入。

### Agent 提醒

- 接收最多每 10 秒一次的状态快照。
- 久坐、坐姿、饮水和疲劳候选评分。
- 单次只选择优先级最高的提醒。
- 全局冷却、分类忽略冷却、有效期与反馈记录。
- LLM 不参与“是否提醒”的决定。

### Agent 日记

- `DiaryCurator` 聚合饮水、休息、主动标记、有效工作段和集中疲劳线索。
- 单次噪声不会直接进入日记。
- Router 配置完整时生成自然语言；失败或无 Key 时使用模板。
- 支持查询、人工编辑、重新生成和删除。

## 主要接口

```text
POST   /v1/auth/register
POST   /v1/auth/login
POST   /v1/auth/refresh
POST   /v1/auth/logout
DELETE /v1/account

POST   /v1/devices
GET    /v1/devices
POST   /v1/sessions
POST   /v1/sessions/{id}/finish

POST   /v1/events/batch
GET    /v1/timeline
POST   /v1/state-snapshots

GET    /v1/reminders/pending
POST   /v1/reminders/{id}/respond

POST   /v1/diaries/{date}/generate
GET    /v1/diaries/{date}
PATCH  /v1/diaries/{date}
DELETE /v1/diaries/{date}
WS     /v1/stream
```

## 验证

执行：

```powershell
cd apps/backend
uv sync --dev --no-editable
.\.venv\Scripts\python.exe -m pytest -q
```

结果：`4 passed`。

测试覆盖：

- 注册、登录、用户信息与 refresh token 单次轮换。
- 重复邮箱拒绝。
- 事件幂等和日记生成。
- 多个提醒条件同时满足时只产生最高优先级项。
- 全局提醒冷却。

## 环境备注

项目路径包含中文。Python 3.11 在 Windows 上可能用 GBK 解码 editable-install 的 UTF-8 `.pth`，因此统一使用 `uv sync --no-editable`。

## 下一步

完成 Web 框架、视觉模型适配、事件上传和提醒/日记界面，并通过 TypeScript 构建。

