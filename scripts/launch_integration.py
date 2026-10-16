"""Verify the Launch single-process layout with isolated data and a real restart."""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import date
import os
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from uuid import uuid4

import httpx

ROOT = Path(__file__).resolve().parents[1]


def checked(args, cwd, env):
    subprocess.run(args, cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-android", action="store_true")
    parser.add_argument("--gradle", type=Path, help="Verified local Gradle executable")
    args = parser.parse_args()
    pnpm = shutil.which("pnpm.cmd") or shutil.which("pnpm")
    if not pnpm:
        raise RuntimeError("pnpm is required")
    checked([pnpm, "build:web"], ROOT, os.environ.copy())
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    with tempfile.TemporaryDirectory(prefix="lookbuddy-launch-") as folder:
        storage = Path(folder)
        database = storage / "lookbuddy.db"
        env = {**os.environ, "LOOKBUDDY_ENV": "development",
               "LOOKBUDDY_DATABASE_URL": f"sqlite:///{database.as_posix()}",
               "LOOKBUDDY_JWT_SECRET": secrets.token_urlsafe(48),
               "LOOKBUDDY_WEB_DIST": str(ROOT / "apps/web/dist"),
               "LOOKBUDDY_SCHEDULER_ENABLED": "true",
               "LOOKBUDDY_INTEGRATION_BASE_URL": origin,
               "LOOKBUDDY_ANDROID_INTEGRATION_BASE_URL": origin + "/"}
        process = None

        def stop():
            nonlocal process
            if process:
                if os.name == "nt":
                    # Windows venv python.exe is a launcher with a child process.
                    # Kill only this owned tree, or a 'restart' may hit the old server.
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                   check=True, capture_output=True)
                else:
                    process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                process = None
                with socket.socket() as probe:
                    probe.settimeout(1)
                    if probe.connect_ex(("127.0.0.1", port)) == 0:
                        raise RuntimeError("Backend port remained open after stopping owned process tree")

        def start():
            nonlocal process
            process = subprocess.Popen(
                [sys.executable, "-m", "uvicorn", "lookbuddy.main:app", "--app-dir", "src",
                 "--host", "127.0.0.1", "--port", str(port)],
                cwd=ROOT / "apps/backend", env=env,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Isolated backend exited during startup")
                try:
                    if httpx.get(origin + "/health/ready", timeout=1).status_code == 200:
                        return
                except httpx.TransportError:
                    pass
                time.sleep(0.1)
            raise RuntimeError("Backend readiness timed out")

        try:
            start()
            with httpx.Client(base_url=origin, timeout=15) as client:
                assert client.get("/").status_code == 200
                assert client.get("/models/pose_landmarker_lite.task").status_code == 200
                assert client.get("/wasm/vision_wasm_internal.wasm").status_code == 200
                assert client.get("/health/live").status_code == 200
                credentials = {"email": f"launch-{uuid4().hex}@example.com", "password": "launch-integration-test"}
                response = client.post("/v1/auth/register", json={**credentials, "displayName": "Launch persistence"})
                response.raise_for_status()
                headers = {"Authorization": "Bearer " + response.json()["accessToken"]}
                response = client.patch("/v1/preferences", headers=headers, json={"sedentaryMinutes": 90, "breakMinutes": 15})
                response.raise_for_status()
                today = date.today().isoformat()
                client.post(f"/v1/diaries/{today}/generate", headers=headers).raise_for_status()
                stop()
                start()
                response = client.post("/v1/auth/login", json=credentials)
                response.raise_for_status()
                headers = {"Authorization": "Bearer " + response.json()["accessToken"]}
                prefs = client.get("/v1/preferences", headers=headers)
                prefs.raise_for_status()
                assert prefs.json()["sedentaryMinutes"] == 90
                client.get(f"/v1/diaries/{today}", headers=headers).raise_for_status()
                checked([sys.executable, str(ROOT / "apps/backend/tools/backup_sqlite.py"),
                         "--source", str(database), "--output", str(storage / "backup.db")], ROOT, env)
                with closing(sqlite3.connect(storage / "backup.db")) as backup:
                    assert backup.execute("PRAGMA integrity_check").fetchone() == ("ok",)
                print("PASS Web assets, health, account/preferences/diary after process restart, verified backup", flush=True)
                checked([pnpm, "test:web"], ROOT, env)
                if not args.skip_android:
                    android = ROOT / "apps/android-local"
                    executable = str(args.gradle.resolve()) if args.gradle else str(android / "gradlew.bat")
                    checked([executable, "-p", "integration", ":app:assembleDebug", ":app:testDebugUnitTest", ":app:lintDebug", "--offline", "--no-daemon"], android, env)
                client.delete("/v1/account", headers=headers).raise_for_status()
        finally:
            stop()
    print("PASS local Launch integration; cloud volume and device UI acceptance still require a Launch application")


if __name__ == "__main__":
    main()
