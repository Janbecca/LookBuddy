from datetime import datetime, timedelta, timezone
from uuid import uuid4
import pytest

from sqlalchemy import select

from lookbuddy.config import get_settings
from lookbuddy.database import SessionLocal
from lookbuddy.models import WorkSession, DomainEvent
from lookbuddy.product import DemoIdentity, DailyMood, ProductLease, ProductProfile, merged_ms


def login(client, monkeypatch, phone="13800000000", installation=None):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    value = client.post("/v1/auth/demo-phone", json={"phone": phone, "installationId": installation or str(uuid4()), "displayName": "演示用户"})
    assert value.status_code == 200, value.text
    return {"Authorization": "Bearer " + value.json()["accessToken"]}, value.json()


@pytest.mark.parametrize("env", ["development", "competition", "production"])
def test_demo_phone_login_controlled_by_demo_flag(client, monkeypatch, env):
    monkeypatch.setattr(get_settings(), "env", env)
    monkeypatch.setattr(get_settings(), "demo_login_enabled", False)
    body = {"phone": "13800000000", "installationId": str(uuid4())}
    assert client.post("/v1/auth/demo-phone", json=body).status_code == 403
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    assert client.post("/v1/auth/demo-phone", json=body).status_code == 200


@pytest.mark.parametrize("phone,code", [("", ""), ("abc", "wrong"), ("随便填写！", "随便验证码"), ("123", "000000"), ("   ", "!@#$")])
def test_demo_accepts_arbitrary_inputs(client, monkeypatch, phone, code):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    body = {"phone": phone, "code": code, "installationId": str(uuid4())}
    first = client.post("/v1/auth/demo-phone", json=body)
    assert first.status_code == 200, first.text
    again = client.post("/v1/auth/demo-phone", json={**body, "code": "different-code"})
    assert again.status_code == 200
    assert again.json()["user"]["id"] == first.json()["user"]["id"]
    monkeypatch.setattr(get_settings(), "env", "production")
    production = client.post("/v1/auth/demo-phone", json=body)
    assert production.status_code == 200, production.text
    assert production.json()["user"]["id"] == first.json()["user"]["id"]


def test_demo_allows_omitting_phone_and_code(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    assert client.post("/v1/auth/demo-phone", json={"installationId": str(uuid4())}).status_code == 200


def test_demo_isolation_and_profile(client, monkeypatch):
    install = str(uuid4())
    auth, first = login(client, monkeypatch, installation=install)
    _, same = login(client, monkeypatch, installation=install)
    other, second = login(client, monkeypatch)
    assert first["user"]["id"] == same["user"]["id"] != second["user"]["id"]
    assert client.patch("/v1/me", headers=auth, json={"displayName": "新昵称", "timezone": "Asia/Shanghai"}).status_code == 200
    assert client.get("/v1/profile", headers=auth).json()["demo"] is True
    assert client.get("/v1/profile", headers=other).json()["displayName"] != "新昵称"
    assert client.patch("/v1/me", headers=auth, json={"timezone": "invalid/zone"}).status_code == 422
    assert client.patch("/v1/work-schedule", headers=auth, json={"start": "22:00", "end": "06:00"}).status_code == 200
    assert client.get("/v1/work-schedule", headers=auth).json()["start"] == "22:00"
    assert client.patch("/v1/work-schedule", headers=auth, json={"start": "09:00", "end": "09:00"}).status_code == 422


def test_mood_upsert_delete_and_no_rewards(client, monkeypatch):
    auth, _ = login(client, monkeypatch)
    for value in ["Happy", "Sad"]:
        assert client.put("/v1/moods/2026-01-01", headers=auth, json={"mood": value}).status_code == 200
    result = client.get("/v1/moods?start=2026-01-01&end=2026-01-07", headers=auth).json()
    assert len(result) == 1 and result[0]["mood"] == "Sad" and result[0]["source"] == "self_report"
    assert client.get("/v1/learning/rewards", headers=auth).json() == []
    assert client.delete("/v1/moods/2026-01-01", headers=auth).status_code == 204
    assert client.get("/v1/moods?start=2026-01-01&end=2026-01-07", headers=auth).json() == []


def test_session_mark_diary_and_account_delete(client, monkeypatch):
    auth, tokens = login(client, monkeypatch)
    owner = {"installationId": str(uuid4())}
    first = client.post("/v1/work/start", headers=auth, json=owner).json()
    assert client.post("/v1/work/start", headers=auth, json=owner).json()["id"] == first["id"]
    assert client.get("/v1/sessions/current", headers=auth).json()["id"] == first["id"]
    now = datetime.now(timezone.utc)
    day = now.astimezone(__import__('zoneinfo').ZoneInfo("Asia/Shanghai")).date().isoformat()
    body = {"eventId": "mark_test_12345", "occurredAt": now.isoformat(), "note": "值得记住"}
    assert client.post("/v1/marks", headers=auth, json=body).json()["duplicate"] is False
    assert client.post("/v1/marks", headers=auth, json=body).json()["duplicate"] is True
    assert client.post("/v1/marks", headers=auth, json={**body, "note": "conflict"}).status_code == 409
    assert client.get(f"/v1/diary-page/{day}", headers=auth).json()["entries"][0]["title"] == "值得记住"
    assert client.post(f"/v1/diaries/{day}/generate", headers=auth).status_code == 200
    client.patch(f"/v1/diaries/{day}", headers=auth, json={"content": "用户保留的版本"})
    assert client.post(f"/v1/diaries/{day}/generate", headers=auth).json()["content"] == "用户保留的版本"
    finish = client.post(f"/v1/sessions/{first['id']}/finish", headers=auth)
    assert finish.status_code == 200
    assert client.post(f"/v1/sessions/{first['id']}/finish", headers=auth).json()["endedAt"] == finish.json()["endedAt"]
    assert client.get("/v1/sessions/current", headers=auth).json() is None
    client.put(f"/v1/moods/{day}", headers=auth, json={"mood": "Calm"})
    client.patch("/v1/work-schedule", headers=auth, json={"start": "08:00", "end": "17:00"})
    assert client.delete("/v1/account", headers=auth).status_code == 204
    with SessionLocal() as db:
        for model in [DemoIdentity, DailyMood, ProductProfile, ProductLease]:
            assert db.scalar(select(model).where(model.user_id == tokens["user"]["id"])) is None


def test_heartbeat_expiry_does_not_invent_work(client, monkeypatch):
    auth, _ = login(client, monkeypatch)
    first = client.post("/v1/work/start", headers=auth, json={"installationId": str(uuid4())}).json()
    with SessionLocal() as db:
        lease = db.scalar(select(ProductLease))
        lease.heartbeat_at = datetime.now(timezone.utc) - timedelta(minutes=5)
        db.get(WorkSession, first["id"]).started_at = lease.heartbeat_at - timedelta(seconds=10)
        db.commit()
    assert client.get("/v1/sessions/current", headers=auth).json() is None
    assert client.get("/v1/home/summary", headers=auth).json()["workMs"] <= 11000


def test_overlap_duration_and_greeting(client, monkeypatch):
    a = datetime(2026, 1, 1, tzinfo=timezone.utc)
    assert merged_ms([(a, a+timedelta(hours=2)), (a+timedelta(hours=1), a+timedelta(hours=3))]) == 10800000
    auth, _ = login(client, monkeypatch)
    monkeypatch.setattr(get_settings(), "greeting_api_key", None)
    result = client.post("/buddy/greeting", headers=auth, json={"scene": "morning_unknown"})
    assert result.status_code == 200 and result.json()["source"] == "fallback"
    assert client.post("/v1/buddy/greeting", headers=auth, json={"recent_message": result.json()["message"]}).json()["show"] is False
    assert client.post("/buddy/greeting", headers=auth, json={"scene": "evening_rain"}).json()["show"] is False


def test_delayed_mark_never_attaches_to_new_session(client, monkeypatch):
    auth, _ = login(client, monkeypatch)
    client.post("/v1/work/start", headers=auth, json={"installationId": str(uuid4())})
    body = {"eventId": "mark_old_offline", "occurredAt": (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()}
    assert client.post("/v1/marks", headers=auth, json=body).status_code == 200
    with SessionLocal() as db:
        assert db.get(DomainEvent, body["eventId"]).session_id is None
