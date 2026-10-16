# LookBuddy 关心语后端

轻量本地后端，为 Android 端提供 GLM 关心语生成接口。

## 特点

- 仅使用 Python 标准库，无需 pip install
- GLM Base URL / Model / API Key 均通过环境变量配置
- 使用智谱开放平台标准 API（适用于自建应用），不使用 Coding Plan 专用额度
- 后端校验 GLM 返回格式，失败时使用符合相同规则的固定兜底文案

## 快速开始

### 1. 配置环境变量

```powershell
cd backend
copy .env.example .env
```

用文本编辑器打开 `.env`，填入：

```
GLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
GLM_MODEL=glm-4-flash
GLM_API_KEY=你的API Key
PORT=5210
```

> API Key 从 https://open.bigmodel.cn/ → 管理控制台 → API Keys 获取。

### 2. 启动后端

```powershell
python app.py
```

或双击 `start.bat`。

启动后会显示：

```
====================================================
  LookBuddy 关心语后端
====================================================
  本机:     http://127.0.0.1:5210
  局域网:   http://192.168.x.x:5210      ← 记下这个 IP
  模型:     glm-4-flash
  API Key:  已设置
  天气来源: 模拟（Phase 1）
  场景:     morning_seated, morning_unknown, evening_rain
====================================================
```

### 3. 验证

浏览器打开 http://127.0.0.1:5210/health ，确认服务正常。

测试关心语接口：

```powershell
curl -X POST http://127.0.0.1:5210/buddy/greeting `
  -H "Content-Type: application/json" `
  -d '{\"scene\":\"morning_seated\"}'
```

返回示例：

```json
{
  "show": true,
  "message": "今天气温约26度，坐下来慢慢进入工作状态就好，不用急，我陪你。",
  "source": "glm",
  "scene_label": "模拟·早间已落座",
  "weather_source": "mock"
}
```

- `"source": "glm"` — GLM 真实生成
- `"source": "fallback"` — GLM 失败，使用兜底文案

## API

### POST /buddy/greeting

请求：

```json
{
  "scene": "morning_seated",
  "recent_message": ""
}
```

场景说明：

| scene | 说明 |
| --- | --- |
| `morning_seated` | 模拟·早间已落座 |
| `morning_unknown` | 模拟·早间状态未知 |
| `evening_rain` | 模拟·下班前正在下雨 |

响应：

```json
{
  "show": true,
  "message": "关心语文案",
  "source": "glm",
  "scene_label": "模拟·早间已落座",
  "weather_source": "mock"
}
```

### GET /health

健康检查，返回服务状态和配置信息。

## 当前状态

| 项目 | 状态 |
| --- | --- |
| 后端服务 | ✅ 已实现 |
| GLM 调用 | ✅ 标准接口，需配置 API Key |
| 返回格式校验 | ✅ 已实现 |
| 兜底文案 | ✅ 已实现 |
| 天气数据 | 🔶 模拟（Phase 1） |
| 时间规则 | 🔶 模拟场景标注 |

## 文件

- `app.py` — 后端主程序
- `.env.example` — 环境变量模板
- `start.bat` — Windows 一键启动
- `requirements.txt` — 无第三方依赖说明
