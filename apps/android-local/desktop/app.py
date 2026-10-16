"""LookBuddy Windows desktop pet and local Android sync service."""

from __future__ import annotations

import argparse
import hmac
import http.server
import json
import os
import queue
import secrets
import socket
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import tkinter as tk
from PIL import Image, ImageTk

ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = ROOT.parent
DEFAULT_ASSET = ROOT / "assets" / "buddy.webp"
DEFAULT_PORT = 5211
TRANSPARENT = "#010203"
WINDOW_WIDTH = 360
WINDOW_HEIGHT = 390
MAX_BODY_BYTES = 64 * 1024


def runtime_dir() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home()))
    path = base / "LookBuddy"
    path.mkdir(parents=True, exist_ok=True)
    return path


def load_or_create_token() -> str:
    configured = os.environ.get("LOOKBUDDY_DESKTOP_TOKEN", "").strip()
    if configured:
        return configured
    path = runtime_dir() / "desktop-token"
    if path.exists():
        return path.read_text(encoding="utf-8").strip()
    token = secrets.token_urlsafe(32)
    path.write_text(token, encoding="utf-8")
    return token


def local_ip() -> str:
    try:
        addresses = socket.gethostbyname_ex(socket.gethostname())[2]
        return next(ip for ip in addresses if not ip.startswith("127."))
    except (OSError, StopIteration):
        return "127.0.0.1"


def update_property(lines: list[str], key: str, value: str) -> list[str]:
    replacement = f"{key}={value}"
    for index, line in enumerate(lines):
        if line.strip().startswith(f"{key}="):
            lines[index] = replacement
            return lines
    if lines and lines[-1].strip():
        lines.append("")
    lines.append(replacement)
    return lines


def write_android_config(mode: str, port: int, token: str) -> None:
    properties = PROJECT_ROOT / "local.properties"
    lines = properties.read_text(encoding="utf-8").splitlines() if properties.exists() else []
    host = "10.0.2.2" if mode == "emulator" else local_ip()
    lines = update_property(lines, "lookbuddy.desktop.url", f"http://{host}:{port}")
    lines = update_property(lines, "lookbuddy.desktop.token", token)
    properties.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Android 本地配置已写入: http://{host}:{port}")
    print("请重新构建并安装 Android App。token 仅保存在 local.properties。")


@dataclass
class DesktopState:
    lock: threading.Lock = field(default_factory=threading.Lock)
    revision: int = 0
    connected: bool = False
    client_id: str = ""
    scene: str = "idle"
    working: bool = False
    mood: str = "calm"
    message: str = ""
    last_seen_at: float | None = None

    def sync(self, body: dict[str, Any]) -> tuple[dict[str, Any], str | None]:
        with self.lock:
            was_connected = self.connected
            old_message = self.message
            self.connected = True
            self.client_id = str(body.get("client_id", "android"))[:80]
            self.scene = str(body.get("scene", "idle"))[:80]
            self.working = bool(body.get("working", False))
            self.mood = str(body.get("mood", "calm"))[:40]
            incoming = str(body.get("message") or "").strip()[:160]
            if incoming:
                self.message = incoming
            self.last_seen_at = time.time()
            self.revision += 1
            bubble = None
            if not was_connected:
                bubble = "手机已连接，我会在桌面陪你~"
            elif incoming and incoming != old_message:
                bubble = incoming
            return self.snapshot_unlocked(), bubble

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            return self.snapshot_unlocked()

    def snapshot_unlocked(self) -> dict[str, Any]:
        return {
            "revision": self.revision,
            "connected": self.connected,
            "client_id": self.client_id,
            "scene": self.scene,
            "working": self.working,
            "mood": self.mood,
            "message": self.message,
            "last_seen_at": self.last_seen_at,
        }


def handler_factory(state: DesktopState, events: queue.Queue[tuple[str, str]], token: str):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def send_json(self, status: int, payload: dict[str, Any]) -> None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-LookBuddy-Token")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.end_headers()
            self.wfile.write(data)

        def authorized(self) -> bool:
            supplied = self.headers.get("X-LookBuddy-Token", "")
            if hmac.compare_digest(supplied, token):
                return True
            self.send_json(401, {"error": "unauthorized"})
            return False

        def read_json(self) -> dict[str, Any] | None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > MAX_BODY_BYTES:
                    self.send_json(413, {"error": "payload too large"})
                    return None
                raw = self.rfile.read(length)
                value = json.loads(raw.decode("utf-8")) if raw else {}
                if not isinstance(value, dict):
                    raise ValueError("body must be an object")
                return value
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
                self.send_json(400, {"error": "invalid json body"})
                return None

        def do_OPTIONS(self) -> None:
            self.send_json(204, {})

        def do_GET(self) -> None:
            if self.path == "/health":
                self.send_json(200, {"status": "ok", "service": "lookbuddy-desktop", "revision": state.snapshot()["revision"]})
                return
            if self.path == "/v1/state" and self.authorized():
                self.send_json(200, {"ok": True, "desktop": state.snapshot()})
                return
            self.send_json(404, {"error": "not found"})

        def do_POST(self) -> None:
            if self.path not in ("/v1/sync", "/v1/reminders"):
                self.send_json(404, {"error": "not found"})
                return
            if not self.authorized():
                return
            body = self.read_json()
            if body is None:
                return
            if self.path == "/v1/sync":
                snapshot, bubble = state.sync(body)
                if bubble:
                    events.put(("message", bubble))
                self.send_json(200, {"ok": True, "desktop": snapshot})
                return
            message = str(body.get("message") or body.get("title") or "该休息一下啦~").strip()[:160]
            events.put(("reminder", message))
            self.send_json(202, {"ok": True, "accepted": True})

        def log_message(self, fmt: str, *args: Any) -> None:
            print(f"[LookBuddy Desktop] {self.client_address[0]} {self.command} {self.path}")

    return Handler


class ReusableThreadingHTTPServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


class DesktopPet:
    def __init__(self, asset_path: Path, events: queue.Queue[tuple[str, str]]) -> None:
        self.events = events
        self.root = tk.Tk()
        self.root.title("LookBuddy")
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.configure(bg=TRANSPARENT)
        if os.name == "nt":
            self.root.wm_attributes("-transparentcolor", TRANSPARENT)
        self.canvas = tk.Canvas(
            self.root,
            width=WINDOW_WIDTH,
            height=WINDOW_HEIGHT,
            bg=TRANSPARENT,
            highlightthickness=0,
            bd=0,
        )
        self.canvas.pack()
        self.frames, self.durations = self.load_frames(asset_path)
        self.frame_index = 0
        self.image_id = self.canvas.create_image(WINDOW_WIDTH // 2, WINDOW_HEIGHT - 4, anchor="s")
        self.bubble_items: list[int] = []
        self.hide_job: str | None = None
        self.drag_origin = (0, 0)
        self.menu = tk.Menu(self.root, tearoff=False)
        self.menu.add_command(label="测试提醒", command=lambda: self.show_bubble("Buddy，起来伸个懒腰吧~", 8000))
        self.menu.add_command(label="隐藏气泡", command=self.hide_bubble)
        self.menu.add_separator()
        self.menu.add_command(label="退出 LookBuddy", command=self.close)
        self.restore_position()
        self.bind_events()
        self.animate()
        self.poll_events()

    def load_frames(self, path: Path) -> tuple[list[ImageTk.PhotoImage], list[int]]:
        if not path.exists():
            raise FileNotFoundError(f"桌宠素材不存在: {path}")
        source = Image.open(path)
        raw_frames: list[Image.Image] = []
        durations: list[int] = []
        union: tuple[int, int, int, int] | None = None
        for index in range(getattr(source, "n_frames", 1)):
            source.seek(index)
            frame = source.convert("RGBA").copy()
            raw_frames.append(frame)
            durations.append(max(20, int(source.info.get("duration", 100))))
            box = frame.getchannel("A").getbbox()
            if box:
                union = box if union is None else (
                    min(union[0], box[0]), min(union[1], box[1]),
                    max(union[2], box[2]), max(union[3], box[3]),
                )
        if union is None:
            raise ValueError("桌宠素材没有可见像素")
        width, height = union[2] - union[0], union[3] - union[1]
        scale = min(300 / width, 300 / height)
        size = (max(1, int(width * scale)), max(1, int(height * scale)))
        frames = [ImageTk.PhotoImage(frame.crop(union).resize(size, Image.Resampling.LANCZOS)) for frame in raw_frames]
        return frames, durations

    def bind_events(self) -> None:
        self.canvas.bind("<ButtonPress-1>", self.start_drag)
        self.canvas.bind("<B1-Motion>", self.drag)
        self.canvas.bind("<ButtonRelease-1>", lambda _event: self.save_position())
        self.canvas.bind("<Button-3>", self.open_menu)
        self.canvas.bind("<Double-Button-1>", lambda _event: self.show_bubble("我会一直在桌面陪你~", 6000))
        self.root.bind("<Escape>", lambda _event: self.close())

    def start_drag(self, event: tk.Event) -> None:
        self.drag_origin = (event.x_root - self.root.winfo_x(), event.y_root - self.root.winfo_y())

    def drag(self, event: tk.Event) -> None:
        x = event.x_root - self.drag_origin[0]
        y = event.y_root - self.drag_origin[1]
        self.root.geometry(f"+{x}+{y}")

    def open_menu(self, event: tk.Event) -> None:
        try:
            self.menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.menu.grab_release()

    def animate(self) -> None:
        self.canvas.itemconfigure(self.image_id, image=self.frames[self.frame_index])
        delay = self.durations[self.frame_index]
        self.frame_index = (self.frame_index + 1) % len(self.frames)
        self.root.after(delay, self.animate)

    def show_bubble(self, message: str, duration_ms: int = 8000) -> None:
        self.hide_bubble()
        text_id = self.canvas.create_text(
            WINDOW_WIDTH // 2,
            48,
            text=message,
            width=292,
            fill="#38291F",
            font=("Microsoft YaHei UI", 11),
            justify="left",
        )
        box = self.canvas.bbox(text_id) or (30, 24, 330, 72)
        padding_x, padding_y = 14, 10
        background = self.rounded_rectangle(
            box[0] - padding_x,
            box[1] - padding_y,
            box[2] + padding_x,
            box[3] + padding_y,
            radius=16,
            fill="#FFFDF9",
            outline="#E8D9CA",
        )
        tail = self.canvas.create_polygon(166, box[3] + padding_y - 1, 186, box[3] + padding_y - 1, 178, box[3] + padding_y + 13, fill="#FFFDF9", outline="#E8D9CA")
        self.canvas.tag_raise(text_id)
        self.bubble_items = [background, tail, text_id]
        self.hide_job = self.root.after(duration_ms, self.hide_bubble)

    def rounded_rectangle(self, x1: int, y1: int, x2: int, y2: int, radius: int, **kwargs: Any) -> int:
        points = [
            x1 + radius, y1, x2 - radius, y1, x2, y1, x2, y1 + radius,
            x2, y2 - radius, x2, y2, x2 - radius, y2, x1 + radius, y2,
            x1, y2, x1, y2 - radius, x1, y1 + radius, x1, y1,
        ]
        return self.canvas.create_polygon(points, smooth=True, splinesteps=24, **kwargs)

    def hide_bubble(self) -> None:
        if self.hide_job:
            self.root.after_cancel(self.hide_job)
            self.hide_job = None
        for item in self.bubble_items:
            self.canvas.delete(item)
        self.bubble_items.clear()

    def poll_events(self) -> None:
        try:
            while True:
                kind, message = self.events.get_nowait()
                self.show_bubble(message, 10_000 if kind == "reminder" else 8_000)
        except queue.Empty:
            pass
        self.root.after(100, self.poll_events)

    def config_path(self) -> Path:
        return runtime_dir() / "desktop.json"

    def restore_position(self) -> None:
        x = self.root.winfo_screenwidth() - WINDOW_WIDTH - 24
        y = self.root.winfo_screenheight() - WINDOW_HEIGHT - 72
        try:
            config = json.loads(self.config_path().read_text(encoding="utf-8"))
            x, y = int(config["x"]), int(config["y"])
        except (OSError, ValueError, KeyError, json.JSONDecodeError):
            pass
        self.root.geometry(f"{WINDOW_WIDTH}x{WINDOW_HEIGHT}+{x}+{y}")

    def save_position(self) -> None:
        payload = {"x": self.root.winfo_x(), "y": self.root.winfo_y()}
        self.config_path().write_text(json.dumps(payload), encoding="utf-8")

    def close(self) -> None:
        self.save_position()
        self.root.destroy()

    def run(self) -> None:
        self.show_bubble("你好呀，我来桌面陪你了~", 5000)
        self.root.mainloop()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LookBuddy Windows 桌宠")
    parser.add_argument("--asset", type=Path, default=DEFAULT_ASSET)
    parser.add_argument("--port", type=int, default=int(os.environ.get("LOOKBUDDY_DESKTOP_PORT", DEFAULT_PORT)))
    parser.add_argument("--headless", action="store_true", help="仅启动同步服务，用于联调")
    parser.add_argument("--write-android-config", choices=("emulator", "lan"))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    token = load_or_create_token()
    if args.write_android_config:
        write_android_config(args.write_android_config, args.port, token)
        return
    state = DesktopState()
    events: queue.Queue[tuple[str, str]] = queue.Queue()
    server = ReusableThreadingHTTPServer(("0.0.0.0", args.port), handler_factory(state, events, token))
    print("=" * 52)
    print("  LookBuddy Windows 桌宠")
    print(f"  本机:   http://127.0.0.1:{args.port}")
    print(f"  局域网: http://{local_ip()}:{args.port}")
    print("  鉴权:   X-LookBuddy-Token（已启用）")
    print("  交互:   拖动移动，双击对话，右键菜单")
    print("=" * 52)
    if args.headless:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
        return
    thread = threading.Thread(target=server.serve_forever, name="lookbuddy-http", daemon=True)
    thread.start()
    try:
        DesktopPet(args.asset, events).run()
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
