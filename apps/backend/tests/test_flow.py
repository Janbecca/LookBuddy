from datetime import datetime, timedelta, timezone


def create_session(client, auth):
    device = client.post("/v1/devices", headers=auth, json={"name": "Web Camera", "platform": "web"})
    assert device.status_code == 201, device.text
    work_session = client.post("/v1/sessions", headers=auth, json={"deviceId": device.json()["id"]})
    assert work_session.status_code == 201, work_session.text
    return device.json()["id"], work_session.json()["id"]


def test_event_is_idempotent_and_diary_is_generated(client, auth):
    device_id, session_id = create_session(client, auth)
    # The default test user is in Asia/Shanghai; diary dates are local calendar dates.
    now = datetime.now(timezone(timedelta(hours=8)))
    event = {
        "schema": "lookbuddy.event",
        "version": 1,
        "eventId": "evt_drink_0001",
        "type": "behavior.drink_detected",
        "deviceId": device_id,
        "sessionId": session_id,
        "seq": 1,
        "occurredAt": now.isoformat(),
        "source": {"kind": "web_vision", "provider": "lookbuddy", "sdk": "webcam"},
        "confidence": 0.9,
        "payload": {"dailyCount": 1},
        "evidence": {"method": "wrist_mouth_sequence"},
        "privacy": {"containsImage": False, "retention": "event_only"},
    }
    first = client.post("/v1/events/batch", headers=auth, json={"events": [event]})
    assert first.status_code == 200, first.text
    assert first.json() == {"accepted": 1, "duplicates": 0}
    second = client.post("/v1/events/batch", headers=auth, json={"events": [event]})
    assert second.json() == {"accepted": 0, "duplicates": 1}

    diary = client.post(f"/v1/diaries/{now.date().isoformat()}/generate", headers=auth)
    assert diary.status_code == 200, diary.text
    assert diary.json()["candidates"][0]["kind"] == "hydration"
    assert "1 次饮水" in diary.json()["content"]


def test_diary_uses_user_local_date_for_offset_event(client, auth):
    device_id, session_id = create_session(client, auth)
    shanghai = timezone(timedelta(hours=8))
    local_time = datetime(2026, 10, 4, 23, 30, tzinfo=shanghai)
    event = {
        "schema": "lookbuddy.event",
        "version": 1,
        "eventId": "evt_local_memory_0001",
        "type": "memory.marked",
        "deviceId": device_id,
        "sessionId": session_id,
        "seq": 9,
        "occurredAt": local_time.isoformat(),
        "source": {"kind": "test", "provider": "lookbuddy"},
        "payload": {"note": "上海时区的夜间记录"},
    }
    response = client.post("/v1/events/batch", headers=auth, json={"events": [event]})
    assert response.status_code == 200, response.text
    diary = client.post("/v1/diaries/2026-10-04/generate", headers=auth)
    assert diary.status_code == 200, diary.text
    assert "上海时区的夜间记录" in diary.json()["content"]


def test_snapshot_creates_only_one_highest_priority_reminder(client, auth):
    _, session_id = create_session(client, auth)
    now = datetime.now(timezone.utc)
    response = client.post(
        "/v1/state-snapshots",
        headers=auth,
        json={
            "sessionId": session_id,
            "capturedAt": now.isoformat(),
            "presence": "online",
            "sedentaryMs": 50 * 60 * 1000,
            "badPostureRatio5m": 0.45,
            "badPostureStreakMs": 70000,
            "fatigueSignals10m": 3,
        },
    )
    assert response.status_code == 200, response.text
    reminder = response.json()["reminder"]
    assert reminder["category"] == "fatigue"
    assert reminder["reasonCodes"] == ["fatigue_signals_repeated"]

    cooldown = client.post(
        "/v1/state-snapshots",
        headers=auth,
        json={
            "sessionId": session_id,
            "capturedAt": now.isoformat(),
            "presence": "online",
            "sedentaryMs": 51 * 60 * 1000,
            "fatigueSignals10m": 3,
        },
    )
    assert cooldown.status_code == 200
    assert cooldown.json()["reminder"] is None
