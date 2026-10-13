from datetime import datetime, timedelta, timezone


def demo(client, auth, category="posture"):
    session = client.post("/v1/sessions", headers=auth, json={}).json()["id"]
    response = client.post("/v1/debug/interventions", headers=auth, json={"sessionId": session, "category": category})
    assert response.status_code == 201, response.text
    value = response.json()
    return value["id"], datetime.fromisoformat(value["displayedAt"])


def frame(client, auth, intervention_id, start, second, posture="normal", presence="present", **extra):
    payload = {
        "frameId": f"frame-{second}", "capturedAt": (start + timedelta(seconds=second)).isoformat(),
        "source": "simulated", "cameraHealthy": True, "quality": "observable",
        "posture": posture, "presence": presence, **extra,
    }
    response = client.post(f"/v1/interventions/{intervention_id}/samples", headers=auth, json=payload)
    assert response.status_code == 200, response.text
    return response.json()


def test_recovery_20s_and_followup_120s_are_persisted_and_idempotent(client, auth):
    intervention_id, start = demo(client, auth)
    assert start.tzinfo is not None
    for second in range(0, 20, 2):
        assert frame(client, auth, intervention_id, start, second)["intervention"]["status"] == "verifying"
    assert frame(client, auth, intervention_id, start, 20)["intervention"]["status"] == "monitoring"
    for second in range(22, 140, 2):
        frame(client, auth, intervention_id, start, second)
    result = frame(client, auth, intervention_id, start, 140)
    assert result["intervention"]["status"] == "improved"
    assert frame(client, auth, intervention_id, start, 140)["accepted"] is False
    stored = client.get(f"/v1/interventions/{intervention_id}", headers=auth).json()
    assert stored["context"]["verification"]["completedAt"] is not None
    observations = client.get(f"/v1/interventions/{intervention_id}/observations", headers=auth).json()
    assert sum(item["kind"] == "action_completed" for item in observations) == 1
    assert sum(item["kind"] == "improved" for item in observations) == 1
    assert client.get("/v1/timeline", headers=auth).json() == []  # no demo pollution


def test_recurrence_requires_continuous_abnormal_60s(client, auth):
    intervention_id, start = demo(client, auth)
    for second in range(0, 22, 2):
        frame(client, auth, intervention_id, start, second)
    for second in range(22, 82, 2):
        assert frame(client, auth, intervention_id, start, second, posture="bad")["intervention"]["status"] == "monitoring"
    assert frame(client, auth, intervention_id, start, 82, posture="bad")["intervention"]["status"] == "recurred"


def test_gaps_reset_recovery_and_make_followup_unknown(client, auth):
    intervention_id, start = demo(client, auth)
    frame(client, auth, intervention_id, start, 0)
    frame(client, auth, intervention_id, start, 2)
    assert frame(client, auth, intervention_id, start, 30)["intervention"]["status"] == "verifying"
    # The gap frame only clears continuity; recovery starts on the next valid frame.
    for second in range(32, 54, 2):
        result = frame(client, auth, intervention_id, start, second)
    assert result["intervention"]["status"] == "monitoring"
    assert frame(client, auth, intervention_id, start, 60)["intervention"]["status"] == "unknown"


def test_leaving_without_standing_is_not_break_completion(client, auth):
    intervention_id, start = demo(client, auth, "sedentary")
    assert frame(client, auth, intervention_id, start, 0, presence="seated")["intervention"]["status"] == "observing"
    value = frame(client, auth, intervention_id, start, 2, presence="away", posture="unknown", quality="insufficient")
    assert value["intervention"]["status"] == "unknown"
    assert value["intervention"]["context"]["verification"]["outcomeReason"] == "left_frame_without_verified_standing"


def test_verified_standing_and_configured_break_duration(client, auth):
    client.patch("/v1/preferences", headers=auth, json={"breakMinutes": 5})
    intervention_id, start = demo(client, auth, "sedentary")
    frame(client, auth, intervention_id, start, 0, presence="standing", standingConfidence=0.8)
    for second in range(2, 302, 2):
        result = frame(client, auth, intervention_id, start, second, presence="away", quality="insufficient", posture="unknown")
        assert result["intervention"]["status"] == "verifying"
    result = frame(client, auth, intervention_id, start, 302, presence="away", quality="insufficient", posture="unknown")
    assert result["intervention"]["status"] == "improved"


def test_return_and_camera_loss_do_not_complete_break(client, auth):
    for returning in (True, False):
        intervention_id, start = demo(client, auth, "sedentary")
        frame(client, auth, intervention_id, start, 0, presence="standing", standingConfidence=0.9)
        frame(client, auth, intervention_id, start, 2, presence="away")
        result = frame(client, auth, intervention_id, start, 4, presence="seated" if returning else "away", cameraHealthy=returning)
        assert result["intervention"]["status"] == ("interrupted" if returning else "unknown")


def test_evidence_order_conflict_and_foreign_user_protection(client, auth):
    intervention_id, start = demo(client, auth)
    frame(client, auth, intervention_id, start, 0)
    payload = {"frameId": "frame-0", "capturedAt": start.isoformat(), "source": "simulated", "quality": "observable", "presence": "present", "posture": "bad"}
    assert client.post(f"/v1/interventions/{intervention_id}/samples", headers=auth, json=payload).status_code == 409
    payload["frameId"] = "earlier"
    payload["capturedAt"] = (start - timedelta(seconds=1)).isoformat()
    assert client.post(f"/v1/interventions/{intervention_id}/samples", headers=auth, json=payload).status_code == 422
    tokens = client.post("/v1/auth/register", json={"email": "stranger@example.com", "password": "correct-horse", "displayName": "Stranger"}).json()
    foreign = {"Authorization": f"Bearer {tokens['accessToken']}"}
    assert client.get(f"/v1/interventions/{intervention_id}", headers=foreign).status_code == 404
    assert client.get(f"/v1/interventions/{intervention_id}/observations", headers=foreign).status_code == 404
    assert client.post(f"/v1/interventions/{intervention_id}/samples", headers=foreign, json=payload).status_code == 404


def test_live_reminder_link_and_simulation_rejection(client, auth):
    session = client.post("/v1/sessions", headers=auth, json={}).json()["id"]
    now = datetime.now(timezone.utc)
    response = client.post("/v1/state-snapshots", headers=auth, json={
        "sessionId": session, "capturedAt": now.isoformat(), "presence": "online",
        "badPostureStreakMs": 60_000, "lastDrinkAt": now.isoformat(),
    })
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["reminder"]["id"] == data["intervention"]["id"]
    intervention_id = data["intervention"]["id"]
    displayed = client.post(f"/v1/interventions/{intervention_id}/displayed", headers=auth)
    assert displayed.status_code == 200
    assert client.post(f"/v1/reminders/{intervention_id}/respond", headers=auth, json={"action": "accepted"}).status_code == 200
    assert client.post(f"/v1/reminders/{intervention_id}/respond", headers=auth, json={"action": "accepted"}).status_code == 200
    rejected = client.post(f"/v1/interventions/{intervention_id}/samples", headers=auth, json={
        "frameId": "fake", "capturedAt": datetime.now(timezone.utc).isoformat(), "source": "simulated", "quality": "observable", "presence": "present", "posture": "normal",
    })
    assert rejected.status_code == 422
    client.post(f"/v1/sessions/{session}/finish", headers=auth)
    assert client.get(f"/v1/interventions/{intervention_id}", headers=auth).json()["status"] == "unknown"
