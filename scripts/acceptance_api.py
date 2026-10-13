"""Black-box acceptance test for a running LookBuddy backend.

This script uses only public HTTP endpoints, creates a unique temporary user,
prints every verified capability, and removes the account in a final cleanup.
"""

from __future__ import annotations

import os
from datetime import datetime
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx


BASE_URL = os.getenv("LOOKBUDDY_ACCEPTANCE_BASE_URL", "http://127.0.0.1:8010")


def expect(response: httpx.Response, status: int, label: str) -> dict:
    if response.status_code != status:
        raise AssertionError(f"{label}: expected {status}, got {response.status_code}: {response.text}")
    payload = response.json() if response.content else {}
    print(f"PASS {label:<24} HTTP {response.status_code}")
    return payload


def main() -> None:
    suffix = uuid4().hex[:10]
    email = f"acceptance-{suffix}@example.com"
    auth: dict[str, str] = {}
    account_created = False

    with httpx.Client(base_url=BASE_URL, timeout=15) as client:
        try:
            health = expect(client.get("/health"), 200, "health")
            assert health["status"] == "ok"

            registered = expect(
                client.post(
                    "/v1/auth/register",
                    json={
                        "email": email,
                        "password": "lookbuddy-acceptance",
                        "displayName": "自动验收用户",
                        "timezone": "Asia/Shanghai",
                    },
                ),
                201,
                "register",
            )
            account_created = True
            auth = {"Authorization": f"Bearer {registered['accessToken']}"}
            old_refresh = registered["refreshToken"]

            expect(
                client.post(
                    "/v1/auth/register",
                    json={
                        "email": email,
                        "password": "lookbuddy-acceptance",
                        "displayName": "重复用户",
                    },
                ),
                409,
                "duplicate email",
            )

            current_user = expect(client.get("/v1/me", headers=auth), 200, "authenticated me")
            assert current_user["email"] == email

            refreshed = expect(
                client.post("/v1/auth/refresh", json={"refreshToken": old_refresh}),
                200,
                "refresh rotation",
            )
            auth = {"Authorization": f"Bearer {refreshed['accessToken']}"}
            expect(
                client.post("/v1/auth/refresh", json={"refreshToken": old_refresh}),
                401,
                "reject reused refresh",
            )

            device = expect(
                client.post(
                    "/v1/devices",
                    headers=auth,
                    json={
                        "name": "Acceptance Web Camera",
                        "provider": "eufy",
                        "providerDeviceRef": f"TEST-{suffix}",
                        "platform": "web",
                    },
                ),
                201,
                "create device",
            )
            devices = expect(client.get("/v1/devices", headers=auth), 200, "list devices")
            assert any(item["id"] == device["id"] for item in devices)

            work_session = expect(
                client.post("/v1/sessions", headers=auth, json={"deviceId": device["id"]}),
                201,
                "create work session",
            )

            now = datetime.now(ZoneInfo("Asia/Shanghai"))
            event_id = f"evt_acceptance_{suffix}"
            event = {
                "schema": "lookbuddy.event",
                "version": 1,
                "eventId": event_id,
                "type": "memory.marked",
                "deviceId": device["id"],
                "sessionId": work_session["id"],
                "seq": 1,
                "occurredAt": now.isoformat(),
                "source": {"kind": "acceptance_test", "provider": "lookbuddy", "sdk": "httpx"},
                "confidence": 1,
                "payload": {"note": "完成 LookBuddy 后端自动验收"},
                "evidence": {"method": "explicit_test_event"},
                "privacy": {"containsImage": False, "retention": "event_only"},
            }
            first_ingest = expect(
                client.post("/v1/events/batch", headers=auth, json={"events": [event]}),
                200,
                "ingest domain event",
            )
            assert first_ingest == {"accepted": 1, "duplicates": 0}
            duplicate = expect(
                client.post("/v1/events/batch", headers=auth, json={"events": [event]}),
                200,
                "event idempotency",
            )
            assert duplicate == {"accepted": 0, "duplicates": 1}

            timeline = expect(client.get("/v1/timeline", headers=auth), 200, "event timeline")
            assert any(item["eventId"] == event_id for item in timeline)

            snapshot = expect(
                client.post(
                    "/v1/state-snapshots",
                    headers=auth,
                    json={
                        "sessionId": work_session["id"],
                        "capturedAt": now.isoformat(),
                        "presence": "online",
                        "sedentaryMs": 50 * 60 * 1000,
                        "badPostureRatio5m": 0.45,
                        "badPostureStreakMs": 70_000,
                        "lastDrinkAt": None,
                        "fatigueSignals10m": 3,
                    },
                ),
                200,
                "reminder decision",
            )
            reminder = snapshot["reminder"]
            assert reminder and reminder["category"] == "fatigue"

            pending = expect(client.get("/v1/reminders/pending", headers=auth), 200, "pending reminders")
            assert any(item["id"] == reminder["id"] for item in pending)

            accepted = expect(
                client.post(
                    f"/v1/reminders/{reminder['id']}/respond",
                    headers=auth,
                    json={"action": "accepted"},
                ),
                200,
                "reminder feedback",
            )
            assert accepted["status"] == "accepted"
            expect(
                client.post(
                    f"/v1/reminders/{reminder['id']}/respond",
                    headers=auth,
                    json={"action": "ignored"},
                ),
                409,
                "reject duplicate response",
            )

            diary_date = now.date().isoformat()
            diary = expect(
                client.post(f"/v1/diaries/{diary_date}/generate", headers=auth),
                200,
                "generate diary",
            )
            assert "完成 LookBuddy 后端自动验收" in diary["content"]
            assert any(item["kind"] == "memory" for item in diary["candidates"])

            loaded_diary = expect(client.get(f"/v1/diaries/{diary_date}", headers=auth), 200, "persisted diary")
            assert loaded_diary["id"] == diary["id"]

            edited = expect(
                client.patch(
                    f"/v1/diaries/{diary_date}",
                    headers=auth,
                    json={"content": "今天完成了 LookBuddy 后端自动验收。"},
                ),
                200,
                "edit diary",
            )
            assert edited["status"] == "edited"

            expect(
                client.post(f"/v1/sessions/{work_session['id']}/finish", headers=auth),
                200,
                "finish work session",
            )

            expect(client.delete(f"/v1/diaries/{diary_date}", headers=auth), 204, "delete diary")
            expect(client.get(f"/v1/diaries/{diary_date}", headers=auth), 404, "confirm diary deletion")

            expect(client.delete("/v1/account", headers=auth), 204, "delete account")
            account_created = False
            expect(client.get("/v1/me", headers=auth), 401, "confirm account deletion")
        finally:
            if account_created and auth:
                client.delete("/v1/account", headers=auth)

    print("\nACCEPTANCE PASSED: all public backend flows behaved as expected")


if __name__ == "__main__":
    main()

