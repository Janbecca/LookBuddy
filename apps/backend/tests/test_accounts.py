"""Unified username identity tests. No seed endpoints or history migration."""
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import select

from lookbuddy.accounts import AccountName
from lookbuddy.config import get_settings
from lookbuddy.database import SessionLocal
from lookbuddy.models import User


def enter(client, monkeypatch, username, **extra):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    response = client.post("/v1/auth/username", json={"username": username, **extra})
    assert response.status_code == 200, response.text
    pair = response.json()
    return {"Authorization": "Bearer " + pair["accessToken"]}, pair


def test_same_username_is_one_account_across_devices_and_passwords(client, monkeypatch):
    first, pair = enter(client, monkeypatch, "SharedName", password="anything")
    second, same = enter(client, monkeypatch, "  sharedname  ", password="another")
    assert pair["user"]["id"] == same["user"]["id"]
    assert same["user"]["username"] == "sharedname"
    client.patch("/v1/me", headers=first, json={"displayName": "Across devices"})
    assert client.get("/v1/profile", headers=second).json()["displayName"] == "Across devices"
    refreshed = client.post("/v1/auth/refresh", json={"refreshToken": same["refreshToken"]}).json()
    assert refreshed["user"]["username"] == "sharedname"
    with SessionLocal() as db:
        assert len(db.scalars(select(AccountName)).all()) == 1
        assert db.get(User, pair["user"]["id"]).password_hash not in {"anything", "another"}


def test_accounts_are_isolated_for_profile_devices_sessions_and_moods(client, monkeypatch):
    first, a = enter(client, monkeypatch, "13800000000")
    second, b = enter(client, monkeypatch, "second@example.com")
    assert a["user"]["id"] != b["user"]["id"]
    device = client.post("/v1/devices", headers=first, json={"name": "SDK computer"}).json()
    session = client.post("/v1/sessions", headers=first, json={"deviceId": device["id"]}).json()
    client.put("/v1/moods/2026-01-01", headers=first, json={"mood": "Happy"})
    assert client.get("/v1/devices", headers=second).json() == []
    assert client.get("/v1/moods/2026-01-01", headers=second).json() is None
    assert client.post("/v1/sessions", headers=second, json={"deviceId": device["id"]}).status_code == 404
    assert client.post(f"/v1/sessions/{session['id']}/finish", headers=second).status_code == 404


def test_previous_bearer_never_merges_history(client, auth, monkeypatch):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    old_id = client.get("/v1/me", headers=auth).json()["id"]
    device = client.post("/v1/devices", headers=auth, json={"name": "old"}).json()
    response = client.post("/v1/auth/username", headers=auth, json={"username": "fresh"})
    assert response.status_code == 200
    pair = response.json()
    assert pair["user"]["id"] != old_id
    fresh = {"Authorization": "Bearer " + pair["accessToken"]}
    assert client.get("/v1/devices", headers=fresh).json() == []
    assert client.get("/v1/devices", headers=auth).json()[0]["id"] == device["id"]
    assert client.get("/v1/account/merge-archives", headers=fresh).status_code == 404
    assert client.post("/v1/devtools/authorize", headers=fresh).status_code == 404
    # A stale old bearer also cannot block the public username login.
    assert client.post("/v1/auth/username", headers={"Authorization": "Bearer expired"},
                       json={"username": "fresh", "password": "ignored"}).json()["user"]["id"] == pair["user"]["id"]


def test_existing_real_email_can_be_a_username(client, auth, monkeypatch):
    old = client.get("/v1/me", headers=auth).json()
    _, pair = enter(client, monkeypatch, "DEMO@example.com", password="")
    assert pair["user"]["id"] == old["id"]
    assert pair["user"]["username"] == "demo@example.com"


def test_concurrent_first_login_creates_exactly_one_account(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: client.post("/v1/auth/username", json={"username": "race"}), range(4)))
    assert all(r.status_code == 200 for r in results)
    assert len({r.json()["user"]["id"] for r in results}) == 1
    with SessionLocal() as db:
        assert len(db.scalars(select(AccountName).where(AccountName.name == "race")).all()) == 1


def test_disabled_and_production_fail_closed(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", False)
    assert client.post("/v1/auth/username", json={"username": "one"}).status_code == 403
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    monkeypatch.setattr(get_settings(), "env", "production")
    assert client.post("/v1/auth/username", json={"username": "one"}).status_code == 403


@pytest.mark.parametrize("username", ["", "   ", "a\nb", "x" * 321])
def test_invalid_usernames(client, monkeypatch, username):
    monkeypatch.setattr(get_settings(), "demo_login_enabled", True)
    assert client.post("/v1/auth/username", json={"username": username}).status_code == 422
