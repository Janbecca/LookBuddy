#!/usr/bin/env python3
"""Local launcher for the upstream LookBuddy FastAPI backend."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "src"
if str(SOURCE) not in sys.path:
    sys.path.insert(0, str(SOURCE))

LEGACY_ENV = {
    "GLM_BASE_URL": "LOOKBUDDY_GREETING_BASE_URL",
    "GLM_MODEL": "LOOKBUDDY_GREETING_MODEL",
    "GLM_API_KEY": "LOOKBUDDY_GREETING_API_KEY",
}


def load_environment(env_file: Path = ROOT / ".env", *, read_dotenv: bool = True) -> None:
    """Keep process variables authoritative and support the previous GLM names."""
    if read_dotenv:
        from dotenv import load_dotenv

        load_dotenv(env_file, override=False)
    for legacy, canonical in LEGACY_ENV.items():
        if legacy in os.environ and canonical not in os.environ:
            os.environ[canonical] = os.environ[legacy]


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-env", action="store_true", help="Use process variables without reading .env")
    parser.add_argument("--host", default=None, help="Bind address; default HOST or 0.0.0.0 for LAN testing")
    parser.add_argument("--port", type=int, default=None, help="Listen port; default PORT or 5210")
    args = parser.parse_args(argv)
    load_environment(read_dotenv=not args.no_env)
    try:
        port = args.port if args.port is not None else int(os.environ.get("PORT", "5210"))
    except ValueError:
        parser.error("PORT must be an integer")
    if not 1 <= port <= 65535:
        parser.error("Port must be between 1 and 65535")
    host = args.host or os.environ.get("HOST", "0.0.0.0")

    # Database paths and the local .env must not depend on the launch directory.
    os.chdir(ROOT)
    from lookbuddy.config import Settings, get_settings

    # .env was loaded explicitly above; --no-env must also prevent Settings reads.
    Settings.model_config["env_file"] = None
    get_settings.cache_clear()
    from lookbuddy.main import app
    import uvicorn

    print(f"LookBuddy backend: http://127.0.0.1:{port}/docs", flush=True)
    print("Business APIs and /buddy/greeting require Bearer authentication.", flush=True)
    uvicorn.run(app, host=host, port=port)


if __name__ == "__main__":
    main()
