# LookBuddy 本地完整后端

主实现来自 `Janbecca/LookBuddy` 的 `apps/backend`，固定下载源提交为
`69622817b38c1ebdf7af846cd8fab9e9826f164f`。`src/`、上游测试、
`pyproject.toml`、`uv.lock`、Dockerfile 和数据库备份工具保持该提交原样。

原本地后端只有 GLM 问候语与模拟天气；现在改为运行上游 FastAPI 服务，包含账号、
设备与工作会话、事件、提醒与调度、表情观察、策略学习、日记、心情和首页数据接口。
本地新增部分只负责启动、旧环境变量兼容和使用说明，不另写一套业务规则。

## 安装和启动（Windows）

需要 Python 3.11 或 3.12。首次在当前目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
Copy-Item .env.example .env
.\.venv\Scripts\python.exe app.py
```

已有 `.env` 时请保留原文件，不执行覆盖复制。完成安装后也可双击 `start.bat`。
默认监听 `0.0.0.0:5210`，沿用原来的局域网联调端口；只在本机调试可执行
`python app.py --host 127.0.0.1`。`--port` 可覆盖端口，`--no-env` 完全跳过 `.env`。

- 健康检查：`http://127.0.0.1:5210/health`
- 就绪检查：`http://127.0.0.1:5210/health/ready`
- 交互接口文档：`http://127.0.0.1:5210/docs`

`app.py` 总是从本目录读取 `.env`，SQLite 相对路径也固定相对本目录。
默认数据库为 `lookbuddy.db`，不会上传到 GitHub；不要用真实数据库运行测试。
`pyproject.toml` 是依赖主来源，`requirements.txt` 只引用当前工程。
使用 uv 时可执行 `uv sync --frozen`，随后 `uv run python app.py`。

## 原有配置怎样保留

`python app.py` 支持旧 `GLM_BASE_URL`、`GLM_MODEL`、`GLM_API_KEY`，分别映射为
`LOOKBUDDY_GREETING_BASE_URL`、`LOOKBUDDY_GREETING_MODEL`、`LOOKBUDDY_GREETING_API_KEY`。
新名称显式存在时优先使用新名称；系统环境变量优先于 `.env`。代码、日志及同步记录不保存真实密钥。
日记模型使用独立的 `LOOKBUDDY_ROUTER_*` 配置，不自动共用问候模型。
直接运行 `uvicorn lookbuddy.main:app --app-dir src` 时只支持上游 `LOOKBUDDY_*` 配置。

## 与旧 App 的联调边界

`/buddy/greeting` 路径仍然存在，但与上游一致，需要登录后的
`Authorization: Bearer <accessToken>`。注册使用 `/v1/auth/register`，登录使用
`/v1/auth/login`；旧 App 只发 scene、不带登录令牌的请求会返回 401。
本次只合并后端，没有修改 Android 登录或首页请求，也没有绕过身份校验。

天气当前为 `unavailable`：早间普通问候，`evening_rain` 不展示。
原来的模拟深圳 26 度和雨天内容只保留于本地旧文件备份，不进入运行服务。
五类提醒仍以感知端上传的数据为条件，下载本服务不等于手机已经接通提醒。
上游 eufy 来源的表情入库/动作验证限制也未在本次下载中改动。

## 后续修改入口

| 需求 | 文件 |
| --- | --- |
| 提醒条件和固定话术 | `src/lookbuddy/reminder_policy.py` |
| 表情分类与观察 | `src/lookbuddy/emotion_service.py` |
| 问候生成及首页接口 | `src/lookbuddy/product.py` |
| 问候 Prompt | `src/lookbuddy/greeting_prompt.py` |
| 日记事实筛选和写作 | `src/lookbuddy/diary_service.py` |
| 动作验证、策略、延后任务 | `action_service.py`、`learning.py`、`scheduler.py`（均在 src/lookbuddy 内） |
| API 路由和字段 | `src/lookbuddy/api.py`、`src/lookbuddy/schemas.py` |

## 验证与同步

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

上游完整测试和本地启动兼容测试使用测试数据库。不要在另一个测试实例或同名测试数据库使用期间运行。
Dockerfile 保持上游设计，使用内部 8080 端口，需要按上游部署环境变量配置，不使用本地 `app.py`。

本目录对应实际工程 `D:/Everything/Anker/projects/lookbuddy/backend`。
根据项目约定，合并后的本地版本同步到 `codex/android-local-sync` 的
`apps/android-local/backend`；下载来源仍是 `main` 的 `apps/backend`。
不覆盖 `main`，不混合两套 Android 实现。详情见 `MERGE.md`。
