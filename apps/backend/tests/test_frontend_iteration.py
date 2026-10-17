from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor

import pytest


@pytest.mark.parametrize("mood", ["Happy", "Calm", "Tired", "Sad", "Curious", "Thoughtful", "Excited"])
def test_all_moods_round_trip_delete_and_isolation(client, auth, mood):
    path = "/v1/moods/2026-10-17"
    assert client.put(path, headers=auth, json={"mood": mood}).status_code == 200
    rows = client.get("/v1/moods?start=2026-10-17&end=2026-10-17", headers=auth).json()
    assert [row["mood"] for row in rows] == [mood]
    assert client.get("/v1/learning/rewards", headers=auth).json() == []
    assert client.delete(path, headers=auth).status_code == 204
    assert client.get("/v1/moods?start=2026-10-17&end=2026-10-17", headers=auth).json() == []
    assert client.put(path, headers=auth, json={"mood": "FutureMood"}).status_code == 422


def make_reminder(client, auth):
    session = client.post("/v1/sessions", headers=auth, json={}).json()["id"]
    now = datetime.now(timezone.utc).isoformat()
    result = client.post("/v1/state-snapshots", headers=auth, json={
        "sessionId": session, "capturedAt": now, "presence": "online",
        "badPostureStreakMs": 60_000, "lastDrinkAt": now,
    })
    assert result.status_code == 200, result.text
    return result.json()["reminder"]["id"]


@pytest.mark.parametrize("action", ["accepted", "snoozed", "ignored"])
def test_display_claim_single_winner_retry_and_responses(client, auth, action):
    key = make_reminder(client, auth)
    path = f"/v1/interventions/{key}/displayed"
    winner = {"claimId": "android:installation-a"}
    loser = {"claimId": "web:installation-b"}
    first = client.post(path, headers=auth, json=winner)
    assert first.status_code == 200, first.text
    again = client.post(path, headers=auth, json=winner)
    assert again.status_code == 200
    assert first.json()["displayedAt"] == again.json()["displayedAt"]
    assert client.post(path, headers=auth, json=loser).status_code == 409
    assert client.post(path, headers=auth).status_code == 409
    response_path = f"/v1/reminders/{key}/respond"
    body = {**winner, "action": action, **({"snoozeMinutes": 10} if action == "snoozed" else {})}
    assert client.post(response_path, headers=auth, json={**body, **loser}).status_code == 409
    assert client.post(response_path, headers=auth, json={"action": action}).status_code == 409
    done = client.post(response_path, headers=auth, json=body)
    assert done.status_code == 200, done.text
    assert done.json()["status"] == action
    assert client.post(response_path, headers=auth, json=body).status_code == 200
    assert key not in [r["id"] for r in client.get("/v1/reminders/pending", headers=auth).json()]
    assert client.post(path, headers=auth, json=winner).status_code == 409


def test_pending_available_after_foreground_restart_and_foreign_claim_isolated(client, auth):
    key = make_reminder(client, auth)
    # Pending is durable HTTP state, even when no WebSocket client is connected.
    for _ in range(2):
        assert key in [r["id"] for r in client.get("/v1/reminders/pending", headers=auth).json()]
    tokens = client.post("/v1/auth/register", json={
        "email": "other@example.com", "password": "correct-horse", "displayName": "Other"
    }).json()
    other = {"Authorization": "Bearer " + tokens["accessToken"]}
    assert client.get("/v1/reminders/pending", headers=other).json() == []
    assert client.post(f"/v1/interventions/{key}/displayed", headers=other,
                       json={"claimId": "android:installation-a"}).status_code == 404


def test_simultaneous_clients_have_exactly_one_display_winner(client, auth):
    key = make_reminder(client, auth)
    def claim(name):
        return client.post(f"/v1/interventions/{key}/displayed", headers=auth, json={"claimId": name})
    with ThreadPoolExecutor(max_workers=2) as pool:
        result = list(pool.map(claim, ["web:race-client", "android:race-client"]))
    assert sorted(r.status_code for r in result) == [200, 409]
