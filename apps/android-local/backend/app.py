#!/usr/bin/env python3
"""
LookBuddy 关心语后端 — GLM 气泡生成服务

本地运行，手机同 Wi-Fi 联调。
仅使用 Python 标准库，无需 pip install。

用法:
    python app.py            # 读取同目录 .env
    python app.py --no-env   # 不读取 .env（用系统环境变量）
"""
from __future__ import annotations

import http.server
import json
import os
import socket
import sys
import urllib.request
import urllib.error
from datetime import datetime, timezone, timedelta

# ── .env 加载 ────────────────────────────────────────────

def load_env(path: str = ".env") -> None:
    """简易 .env 解析，不依赖 python-dotenv。"""
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))


load_env()

# ── 配置（全部通过环境变量，不写入代码）────────────────

GLM_BASE_URL = os.environ.get(
    "GLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4"
).rstrip("/")
GLM_MODEL = os.environ.get("GLM_MODEL", "glm-4-flash")
GLM_API_KEY = os.environ.get("GLM_API_KEY", "")
PORT = int(os.environ.get("PORT", "5210"))

# ── Prompt（用户提供，原样使用）────────────────────────

SYSTEM_PROMPT = """你是 LookBuddy，一位陪伴用户工作的桌面小伙伴。
根据程序提供的场景、天气和在岗状态，生成首页 IP 旁的气泡关心语。

【输入】
- 场景：morning / evening_rain
- 城市：深圳
- 当前当地日期与时间
- 用户设定的下班时间
- 程序判断的提醒资格：eligible
- 今日平均气温，单位 ℃；可能为空
- 今日降雨预报；可能为空
- 当前是否下雨；可能为未知
- 风况及有效天气预警；可能为空
- 天气数据是否有效
- 在岗状态：
  seated：可靠检测到用户已落座
  unknown：摄像头未开启、未检测到或检测不确定
- 最近展示的关心语；可能为空

【触发条件，由程序判断】
早间：
- 当前时间在 06:00（含）至 11:00（不含）之间。
- 本次是当日首次打开 App。
- 每日最多提醒一次。

下班前：
- App 当前在前台。
- 当前时间在设定下班时间前 30 分钟内，且尚未到下班时间。
- 进入此时间段时，或用户在此时间段打开 App 时，
  重新获取实时天气。
- 仅当有效数据明确显示当前正在下雨，才具备提醒资格。
- 每日最多提醒一次；已到下班时间不再补发。

eligible 为 false 时，不生成文案。

【早间文案】
1. 有有效的今日平均气温时，使用自然表达：
   "今天气温约X度"。
   不说"均温"，不把当前温度或最高温度当作今日平均气温。

2. 先轻轻提到天气，再接一个具体的小关心。

3. 已落座 seated：
   - 可以关心坐姿、座位舒适度或进入工作状态。
   - 即使有雨，也不要提醒带伞、出门或通勤。
   - 不要求用户离开座位。
   - 可以提及降雨，但不必强行提及。

4. 状态未知 unknown：
   - 不假设用户已到岗、在家或正在通勤。
   - 今日预报有雨或当前正在下雨时，必须提醒出门带伞。
   - 其他关心应不依赖用户所在位置。

5. 降雨表述必须符合数据：
   今日预报有雨说"今天有雨"；
   只有实时数据确认正在下雨，才说"现在正在下雨"。

6. 风况明显影响出行时，可以简短关心；
   已落座时不加入出行建议。
   除必要的带伞提醒外，避免堆叠多个建议。

【下班前文案】
1. 必须有有效实时数据确认当前正在下雨，否则不展示。
2. 先提到当前降雨，再提醒待会儿下班出门带伞。
3. 即使用户已落座，也可以提醒带伞。
4. 不断言用户一定准时下班，
   不断言当前的雨一定会持续到下班。
5. 不使用今日预报或早间天气替代当前降雨判断。

【数据缺失】
- 早间缺少温度时省略温度，使用其他有效天气信息。
- 早间所有天气数据均不可用时，生成普通陪伴问候。
- 下班前天气不可用、过期或降雨状态未知时，不展示。
- 不编造天气、风力、预警或用户状态。

【语气与长度】
- 自然、温暖、亲近，像熟悉的小伙伴。
- 偶尔加入 Buddy 的存在感，例如"我陪你"，不必每条都加。
- 不播报一串指标、不说教、不喊口号、不要求回复。
- 不推测用户心情、身体感受、衣着或出行方式。
- 最多两句，优先控制在 30～50 个汉字。
- 避免与最近文案重复。
- 不使用 emoji、标题或 Markdown。
- 有有效官方天气预警时，不用轻佻表达淡化风险。

【输出】
仅输出 JSON：
{"show": true, "message": "关心语"}

不满足展示条件时：
{"show": false, "message": ""}"""

# ── 模拟深圳天气（Phase 1，明确标注）──────────────────

SHENZHEN_TZ = timezone(timedelta(hours=8))

MOCK_SCENES: dict[str, dict] = {
    "morning_seated": {
        "scenario": "morning",
        "city": "深圳",
        "time": "08:30",
        "off_work_time": "18:00",
        "eligible": True,
        "avg_temp": 26,
        "rain_forecast": "今日有阵雨",
        "currently_raining": "未知",
        "wind": "微风",
        "warning": "无",
        "weather_valid": True,
        "presence": "seated",
        "label": "模拟·早间已落座",
    },
    "morning_unknown": {
        "scenario": "morning",
        "city": "深圳",
        "time": "08:30",
        "off_work_time": "18:00",
        "eligible": True,
        "avg_temp": 26,
        "rain_forecast": "今日有中雨",
        "currently_raining": "未知",
        "wind": "微风",
        "warning": "无",
        "weather_valid": True,
        "presence": "unknown",
        "label": "模拟·早间状态未知",
    },
    "evening_rain": {
        "scenario": "evening_rain",
        "city": "深圳",
        "time": "17:35",
        "off_work_time": "18:00",
        "eligible": True,
        "avg_temp": 26,
        "rain_forecast": "今日有阵雨",
        "currently_raining": "是",
        "wind": "微风",
        "warning": "无",
        "weather_valid": True,
        "presence": "seated",
        "label": "模拟·下班前正在下雨",
    },
}

# ── 兜底文案（模型失败时使用，遵循相同规则）──────────

FALLBACK: dict[str, str] = {
    "morning_seated": "今天气温约26度，坐下来慢慢进入工作状态就好，不用急，我陪你。",
    "morning_unknown": "今天气温约26度，今天有雨，出门记得带伞哦，路上慢慢来，我陪你。",
    "evening_rain": "现在正在下雨，待会儿下班出门记得带伞，路上小心，我陪你。",
}


# ── GLM 调用 ───────────────────────────────────────────

def build_user_input(scene_data: dict, recent_message: str) -> str:
    """将场景数据格式化为 GLM 用户消息。"""
    now = datetime.now(SHENZHEN_TZ)
    date_str = now.strftime("%Y-%m-%d")
    lines = [
        f"场景：{scene_data['scenario']}",
        f"城市：{scene_data['city']}",
        f"当前当地日期与时间：{date_str} {scene_data['time']}",
        f"用户设定的下班时间：{scene_data['off_work_time']}",
        f"提醒资格：{'eligible' if scene_data['eligible'] else 'ineligible'}",
        f"今日平均气温：{scene_data['avg_temp']}℃" if scene_data["avg_temp"] else "今日平均气温：（空）",
        f"今日降雨预报：{scene_data['rain_forecast']}",
        f"当前是否下雨：{scene_data['currently_raining']}",
        f"风况：{scene_data['wind']}",
        f"有效天气预警：{scene_data['warning']}",
        f"天气数据是否有效：{'是' if scene_data['weather_valid'] else '否'}",
        f"在岗状态：{scene_data['presence']}",
        f"最近展示的关心语：{recent_message if recent_message else '（无）'}",
    ]
    return "\n".join(lines)


def parse_glm_content(content: str) -> dict | None:
    """从 GLM 返回内容中解析 JSON，校验格式。"""
    text = content.strip()
    # 去除可能的 markdown 代码块包裹
    if text.startswith("```"):
        text = text.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
    try:
        obj = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(obj, dict):
        return None
    show = obj.get("show")
    message = obj.get("message")
    # 格式校验：show 必须是 bool，message 必须是 string
    if not isinstance(show, bool) or not isinstance(message, str):
        return None
    # show 为 true 时 message 不能为空
    if show and not message.strip():
        return None
    # message 不能包含 emoji、标题或 Markdown（简单校验）
    if message != message.replace("#", "").replace("*", ""):
        # 允许模型偶尔输出，但记录
        pass
    return {"show": show, "message": message.strip()}


def call_glm(scene_data: dict, recent_message: str) -> dict | None:
    """调用 GLM 标准接口，返回解析后的 dict 或 None（失败时）。"""
    if not GLM_API_KEY:
        print("[LookBuddy] 未设置 GLM_API_KEY，使用兜底文案", file=sys.stderr)
        return None

    user_input = build_user_input(scene_data, recent_message)
    body = json.dumps({
        "model": GLM_MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_input},
        ],
        "temperature": 0.7,
        "response_format": {"type": "json_object"},
    }, ensure_ascii=False).encode("utf-8")

    url = f"{GLM_BASE_URL}/chat/completions"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Authorization", f"Bearer {GLM_API_KEY}")
    req.add_header("Content-Type", "application/json; charset=utf-8")

    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        content = data["choices"][0]["message"]["content"]
        result = parse_glm_content(content)
        if result is None:
            print(f"[LookBuddy] GLM 返回格式异常: {content[:200]}", file=sys.stderr)
        return result
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8", errors="replace")[:300]
        print(f"[LookBuddy] GLM HTTP {e.code}: {err_body}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"[LookBuddy] GLM 调用失败: {e}", file=sys.stderr)
        return None


# ── HTTP Handler ─────────────────────────────────────────


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_json(self, code: int, obj: dict) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        if self.path in ("/", "/health"):
            self._send_json(200, {
                "status": "ok",
                "service": "LookBuddy 关心语后端",
                "model": GLM_MODEL,
                "api_key_set": bool(GLM_API_KEY),
                "scenes": list(MOCK_SCENES.keys()),
                "weather_source": "mock (Phase 1)",
            })
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path != "/buddy/greeting":
            self._send_json(404, {"error": "not found"})
            return

        # 解析请求
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length) if length else b"{}"
            req = json.loads(raw.decode("utf-8")) if raw.strip() else {}
        except Exception:
            self._send_json(400, {"error": "invalid json body"})
            return

        scene = req.get("scene", "morning_seated")
        recent_message = req.get("recent_message", "")
        scene_data = MOCK_SCENES.get(scene)

        if scene_data is None:
            self._send_json(400, {
                "error": f"unknown scene: {scene}",
                "available": list(MOCK_SCENES.keys()),
            })
            return

        print(f"[LookBuddy] 请求 scene={scene} ({scene_data['label']})")

        # 尝试调用 GLM
        result = call_glm(scene_data, recent_message)
        if result is not None:
            print(f"[LookBuddy] GLM 成功: show={result['show']}")
            self._send_json(200, {
                **result,
                "source": "glm",
                "scene_label": scene_data["label"],
                "weather_source": "mock",
            })
            return

        # GLM 失败，使用兜底文案
        fb = FALLBACK.get(scene, FALLBACK["morning_seated"])
        print(f"[LookBuddy] 使用兜底文案: {fb}")
        self._send_json(200, {
            "show": True,
            "message": fb,
            "source": "fallback",
            "scene_label": scene_data["label"],
            "weather_source": "mock",
        })

    def log_message(self, fmt: str, *args) -> None:
        # 简化日志，只记录路径
        print(f"[LookBuddy] {self.address_string()} {self.command} {self.path}")


# ── Main ─────────────────────────────────────────────────


def get_local_ip() -> str:
    """获取本机局域网 IP（用于手机联调）。"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main() -> None:
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    ip = get_local_ip()
    print("=" * 52)
    print("  LookBuddy 关心语后端")
    print("=" * 52)
    print(f"  本机:     http://127.0.0.1:{PORT}")
    print(f"  局域网:   http://{ip}:{PORT}")
    print(f"  模型:     {GLM_MODEL}")
    print(f"  API Key:  {'已设置' if GLM_API_KEY else '未设置（将使用兜底文案）'}")
    print(f"  天气来源: 模拟（Phase 1）")
    print(f"  场景:     {', '.join(MOCK_SCENES.keys())}")
    print("=" * 52)
    print("  按 Ctrl+C 停止")
    print()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n正在停止...")
        server.shutdown()
        print("已停止。")


if __name__ == "__main__":
    main()
