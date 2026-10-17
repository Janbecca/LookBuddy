"""Run the Android product client against an isolated real FastAPI service."""
from pathlib import Path
import argparse
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time

import httpx

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", required=True, type=Path)
    parser.add_argument("--lint", action="store_true", help="Also run the Android lint gate")
    args = parser.parse_args()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    with tempfile.TemporaryDirectory(prefix="lookbuddy-product-") as folder:
        env = {**os.environ, "LOOKBUDDY_ENV": "development",
               "LOOKBUDDY_DATABASE_URL": f"sqlite:///{Path(folder).as_posix()}/test.db",
               "LOOKBUDDY_JWT_SECRET": secrets.token_urlsafe(48),
               "LOOKBUDDY_DEMO_LOGIN_ENABLED": "true",
               "LOOKBUDDY_SCHEDULER_ENABLED": "false",
               "LOOKBUDDY_GREETING_API_KEY": "", "LOOKBUDDY_ROUTER_API_KEY": "",
               "LOOKBUDDY_PRODUCT_INTEGRATION_BASE_URL": origin}
        process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "lookbuddy.main:app", "--app-dir", "src",
             "--host", "127.0.0.1", "--port", str(port)],
            cwd=ROOT / "apps/backend", env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Isolated backend exited")
                try:
                    if httpx.get(origin + "/health/ready", timeout=1).status_code == 200:
                        break
                except httpx.TransportError:
                    pass
                time.sleep(.1)
            else:
                raise RuntimeError("Isolated backend not ready")
            tasks = [":app:assembleDebug", ":app:testDebugUnitTest"]
            if args.lint:
                tasks.append(":app:lintDebug")
            subprocess.run([str(args.gradle), "-p", "apps/android-local/integration",
                            *tasks, "--no-daemon"], cwd=ROOT, env=env, check=True)
        finally:
            if process.poll() is None:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                   check=True, capture_output=True)
                else:
                    process.terminate()
                process.wait(timeout=15)
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", port)) == 0:
                    raise RuntimeError("Owned backend port still open after cleanup")
    print("Product client/backend integration completed; temporary service stopped.")


if __name__ == "__main__":
    main()
