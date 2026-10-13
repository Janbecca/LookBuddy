"""End-to-end API smoke test against a running local backend."""

from datetime import datetime, timezone
from uuid import uuid4

import httpx


BASE_URL = "http://127.0.0.1:8000"


def main() -> None:
    email = f"smoke-{uuid4().hex[:8]}@example.com"
    with httpx.Client(base_url=BASE_URL, timeout=10) as client:
        tokens = client.post(
            "/v1/auth/register",
            json={"email": email, "password": "lookbuddy-smoke", "displayName": "联调用户"},
        )
        tokens.raise_for_status()
        auth = {"Authorization": f"Bearer {tokens.json()['accessToken']}"}

        device = client.post("/v1/devices", headers=auth, json={"name": "Smoke Web", "platform": "web"})
        device.raise_for_status()
        work_session = client.post("/v1/sessions", headers=auth, json={"deviceId": device.json()["id"]})
        work_session.raise_for_status()

        now = datetime.now(timezone.utc)
        event = {
            "schema": "lookbuddy.event",
            "version": 1,
            "eventId": f"evt_{uuid4().hex}",
            "type": "memory.marked",
            "deviceId": device.json()["id"],
            "sessionId": work_session.json()["id"],
            "seq": 1,
            "occurredAt": now.isoformat(),
            "source": {"kind": "event_simulator", "provider": "lookbuddy"},
            "confidence": 1,
            "payload": {"note": "完成三端架构联调"},
            "evidence": {"method": "user_explicit_action"},
            "privacy": {"containsImage": False, "retention": "event_only"},
        }
        ingested = client.post("/v1/events/batch", headers=auth, json={"events": [event]})
        ingested.raise_for_status()

        diary = client.post(f"/v1/diaries/{now.date().isoformat()}/generate", headers=auth)
        diary.raise_for_status()
        assert "完成三端架构联调" in diary.json()["content"]

        deleted = client.delete("/v1/account", headers=auth)
        deleted.raise_for_status()

    print("OK: auth -> device -> session -> event -> diary -> account deletion")


if __name__ == "__main__":
    main()

