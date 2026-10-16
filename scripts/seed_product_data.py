"""Inspect, seed and restore one account through the existing LookBuddy API.

No direct database access, account creation, camera events or production metrics.
"""
from __future__ import annotations

import argparse
from datetime import date, datetime
import getpass
import ipaddress
import json
import os
from pathlib import Path
import sys
import tempfile
import warnings
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

import httpx


class SeedError(Exception):
    pass


def hidden_input(prompt):
    try:
        with warnings.catch_warnings():
            # Do not let getpass silently fall back to an echoed stdin read.
            warnings.simplefilter("error", getpass.GetPassWarning)
            return getpass.getpass(prompt)
    except getpass.GetPassWarning:
        raise SeedError("Hidden input is unavailable. Use an interactive terminal or the token environment variable.") from None


def origin(value: str) -> str:
    try:
        parsed = urlsplit(value)
        parsed.port
        if (not parsed.hostname or parsed.username is not None or parsed.password is not None
                or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
            raise ValueError()
        if parsed.scheme == "https":
            return value.rstrip("/")
        if parsed.scheme == "http" and (parsed.hostname == "localhost"
                or ipaddress.ip_address(parsed.hostname).is_loopback):
            return value.rstrip("/")
    except ValueError:
        pass
    raise SeedError("Backend must be an HTTPS origin (HTTP only for loopback), without /v1 or credentials")


class Api:
    def __init__(self, backend: str, client=None, direct: bool = False):
        self.backend = origin(backend)
        self.client = client or httpx.Client(timeout=20, follow_redirects=False, trust_env=not direct)
        self.token = ""

    def request(self, method, path, body=None):
        try:
            response = self.client.request(method, self.backend + path,
                headers={"Authorization": "Bearer " + self.token} if self.token else {},
                **({"json": body} if body is not None else {}))
        except httpx.HTTPError:
            raise SeedError("Network request failed; a timed-out write may have succeeded. Keep the backup.") from None
        if not 200 <= response.status_code < 300:
            # Never print server bodies, headers, passwords or tokens.
            raise SeedError(f"HTTP {response.status_code}: {method} {path}. Check login, permissions and backend version.")
        return response.json() if response.content else None

    def read(self, path):
        return self.request("GET", path)


def snapshot(api: Api, day: str | None = None):
    profile = api.read("/v1/profile")
    day = day or datetime.now(ZoneInfo(profile["timezone"])).date().isoformat()
    day = date.fromisoformat(day).isoformat()
    schedule = api.read("/v1/work-schedule")
    mood = api.read(f"/v1/moods/{day}")
    return {"backend": api.backend, "userId": profile["id"], "timezone": profile["timezone"], "day": day,
        "before": {"profile": {"displayName": profile["displayName"]},
                   "schedule": {key: schedule[key] for key in ("start", "end")},
                   "mood": {"mood": mood["mood"]} if mood else None}}


def save(path: Path, value: dict, *, create=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    if create:
        # Refuse to destroy an earlier recovery journal.
        with path.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        return
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=".seed-", delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def require_account(actual: str, expected: str):
    if actual != expected:
        raise SeedError("Account mismatch; no writes performed. Inspect the account before retrying.")


def write_value(api, field, value, day):
    if field == "profile":
        api.request("PATCH", "/v1/me", value)
    elif field == "schedule":
        api.request("PATCH", "/v1/work-schedule", value)
    elif field == "mood":
        api.request("DELETE" if value is None else "PUT", f"/v1/moods/{day}", value)
    else:
        raise SeedError("Unknown journal operation")


def seed(api: Api, expected_user: str, backup: Path, day=None):
    journal = snapshot(api, day)
    require_account(journal["userId"], expected_user)
    if date.fromisoformat(journal["day"]) > datetime.now(ZoneInfo(journal["timezone"])).date():
        raise SeedError("Future self-reports are not allowed; no writes performed")
    before = journal["before"]
    journal.update({"version": 1, "attempted": [], "state": "prepared", "after": {
        "profile": {"displayName": "[联调测试] 再次读取云端数据" if before["profile"]["displayName"] == "[联调测试] 云端数据已更新" else "[联调测试] 云端数据已更新"},
        "mood": {"mood": "Sad" if before["mood"] == {"mood": "Happy"} else "Happy"},
        "schedule": {"start": "09:00", "end": "18:00"} if before["schedule"] == {"start": "11:00", "end": "20:00"}
                    else {"start": "11:00", "end": "20:00"}}})
    save(backup, journal, create=True)
    for field in ("profile", "mood", "schedule"):
        # Record intent BEFORE the request, so a lost response remains recoverable.
        journal["attempted"].append(field)
        save(backup, journal)
        write_value(api, field, journal["after"][field], journal["day"])
    current = snapshot(api, journal["day"])
    require_account(current["userId"], expected_user)
    if current["before"] != journal["after"]:
        raise SeedError("Read-back did not match; keep the backup and inspect before restoring")
    journal["state"] = "seeded"
    save(backup, journal)
    return current


def restore(api: Api, expected_user: str, backup: Path):
    journal = json.loads(backup.read_text(encoding="utf-8"))
    if journal.get("version") != 1 or journal.get("backend") != api.backend:
        raise SeedError("Backup version or backend mismatch; no writes performed")
    require_account(journal["userId"], expected_user)
    current = snapshot(api, journal["day"])
    require_account(current["userId"], expected_user)
    attempted = journal["attempted"]
    if len(set(attempted)) != len(attempted) or any(f not in ("profile", "mood", "schedule") for f in attempted):
        raise SeedError("Invalid recovery journal")
    # Refuse to overwrite an edit made on the phone after seeding.
    for field in attempted:
        if current["before"][field] not in (journal["before"][field], journal["after"][field]):
            raise SeedError(f"{field} changed after the test; refusing to overwrite it. Backup retained.")
    for field in reversed(attempted):
        if current["before"][field] != journal["before"][field]:
            write_value(api, field, journal["before"][field], journal["day"])
    result = snapshot(api, journal["day"])
    if any(result["before"][f] != journal["before"][f] for f in attempted):
        raise SeedError("Restore read-back did not match; backup retained")
    journal["state"] = "restored"
    save(backup, journal)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("inspect", "seed", "restore"), nargs="?", default="inspect")
    parser.add_argument("--backend", required=True, help="Cloud HTTPS origin, without /v1")
    parser.add_argument("--auth", choices=("token", "email"), default="token")
    parser.add_argument("--direct", action="store_true", help="Ignore this process's proxy environment")
    parser.add_argument("--confirm-user-id", help="Exact userId returned by inspect; required for writes")
    parser.add_argument("--backup", type=Path, help="Recovery JSON in an ignored output/ directory")
    parser.add_argument("--day", help="Account-local ISO date; default is today")
    args = parser.parse_args(argv)
    if args.command != "inspect" and (not args.confirm_user_id or not args.backup):
        parser.error("seed/restore require --confirm-user-id and --backup")
    if args.command == "restore" and args.day:
        parser.error("restore uses the backup's date; omit --day")
    api = None
    try:
        # Validate and preflight before asking for a credential.
        api = Api(args.backend, direct=args.direct)
        api.read("/health/ready")
        if args.auth == "email":
            email = input("Existing test-account email: ").strip()
            password = hidden_input("Password (not stored): ")
            pair = api.request("POST", "/v1/auth/login", {"email": email, "password": password})
            api.token = pair["accessToken"]
            password = ""
            pair = None
        else:
            api.token = os.environ.get("LOOKBUDDY_TEST_ACCESS_TOKEN") or hidden_input("Current account access token (not stored): ")
            if not api.token.strip():
                raise SeedError("An access token is required; no account will be created automatically")
        if args.command == "seed":
            result = seed(api, args.confirm_user_id, args.backup, args.day)
        elif args.command == "restore":
            result = restore(api, args.confirm_user_id, args.backup)
        else:
            result = snapshot(api, args.day)
        print(json.dumps({key: value for key, value in result.items() if key != "before"}
                         | {"values": result["before"]}, ensure_ascii=False, indent=2))
        if args.command == "inspect":
            print("Read-only inspection complete. Confirm this is the account shown in your frontend before seeding.")
        elif args.command == "seed":
            print("Cloud write/read-back succeeded. Keep the App foreground for 30 seconds; backup retained for restore.")
        else:
            print("Original visible values restored. The recovery journal was retained.")
        return 0
    except (SeedError, OSError, ValueError, KeyError, TypeError, EOFError):
        # Error strings may contain a credential supplied by another library.
        # SeedError is our own sanitized message; other failures stay generic.
        error = sys.exception()
        print(str(error) if isinstance(error, SeedError) else "Local input/backup operation failed. No backup was deleted; inspect before retrying.", file=sys.stderr)
        return 1
    finally:
        if api:
            api.token = ""
            api.client.close()


if __name__ == "__main__":
    raise SystemExit(main())
