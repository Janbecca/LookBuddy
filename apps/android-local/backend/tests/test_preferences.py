from datetime import datetime, timezone

from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import Session

from lookbuddy.migrations import REVISION, REVISIONS, migrate
from lookbuddy.models import Intervention, PolicyState, RewardLedger, SchemaRevision, User, UserPreferences


def test_defaults_validation_and_partial_updates(client, auth):
    defaults = client.get("/v1/preferences", headers=auth)
    assert defaults.status_code == 200
    assert defaults.json()["sedentaryMinutes"] == 60
    assert defaults.json()["breakMinutes"] == 10
    for invalid in [{"sedentaryMinutes": 45}, {"breakMinutes": 2}, {"busy": None}, {"unknown": True}]:
        assert client.patch("/v1/preferences", headers=auth, json=invalid).status_code == 422
    changed = client.patch("/v1/preferences", headers=auth, json={"sedentaryMinutes": 90, "breakMinutes": 20})
    assert changed.status_code == 200, changed.text
    assert changed.json()["updatedAt"] is not None
    client.patch("/v1/preferences", headers=auth, json={"busy": True})
    current = client.get("/v1/preferences", headers=auth).json()
    assert (current["sedentaryMinutes"], current["breakMinutes"], current["busy"]) == (90, 20, True)
    assert client.get("/v1/preferences").status_code == 401


def test_user_isolation_and_deletion(client, auth):
    from lookbuddy.database import engine

    user_id = client.get("/v1/me", headers=auth).json()["id"]
    session_id = client.post("/v1/sessions", headers=auth, json={}).json()["id"]
    client.patch("/v1/preferences", headers=auth, json={"busy": True})
    with Session(engine) as db:
        intervention = Intervention(user_id=user_id, session_id=session_id, category="posture", strategy="light", source="test")
        db.add(intervention)
        db.flush()
        db.add(RewardLedger(user_id=user_id, intervention_id=intervention.id, settlement_key="test", version="v1", amount=0))
        db.add(PolicyState(user_id=user_id, namespace="demo", context_key="test", arm="light"))
        db.commit()
    tokens = client.post("/v1/auth/register", json={"email": "second@example.com", "password": "correct-horse", "displayName": "Second"}).json()
    second = {"Authorization": f"Bearer {tokens['accessToken']}"}
    assert client.get("/v1/preferences", headers=second).json()["busy"] is False
    assert client.delete("/v1/account", headers=auth).status_code == 204
    with Session(engine) as db:
        for model in (UserPreferences, Intervention, RewardLedger, PolicyState):
            assert db.scalar(select(model).where(model.user_id == user_id)) is None
    assert client.get("/v1/me", headers=second).status_code == 200


def test_busy_and_configured_sedentary_threshold(client, auth):
    session_id = client.post("/v1/sessions", headers=auth, json={}).json()["id"]
    snapshot = {
        "sessionId": session_id, "capturedAt": datetime.now(timezone.utc).isoformat(),
        "presence": "online", "sedentaryMs": 50 * 60_000,
        "lastDrinkAt": datetime.now(timezone.utc).isoformat(),
    }
    assert client.post("/v1/state-snapshots", headers=auth, json=snapshot).json()["reminder"] is None
    client.patch("/v1/preferences", headers=auth, json={"sedentaryMinutes": 30, "busy": True})
    assert client.post("/v1/state-snapshots", headers=auth, json=snapshot).json()["reminder"] is None
    client.patch("/v1/preferences", headers=auth, json={"busy": False})
    reminder = client.post("/v1/state-snapshots", headers=auth, json=snapshot).json()["reminder"]
    assert reminder["category"] == "sedentary"
    assert "continuous_presence_30m" in reminder["reasonCodes"]


def test_additive_migration_is_idempotent_and_preserves_existing_data(tmp_path):
    bind = create_engine(f"sqlite:///{tmp_path / 'legacy.db'}")
    # Represent a pre-P0 database with an existing account table.
    User.__table__.create(bind)
    with Session(bind) as db:
        db.add(User(id="usr_existing", email="existing@example.com", display_name="Existing", password_hash="preserved"))
        db.commit()
    migrate(bind)
    migrate(bind)
    with Session(bind) as db:
        assert db.get(User, "usr_existing").password_hash == "preserved"
        assert len(list(db.scalars(select(SchemaRevision)))) == len(REVISIONS)
        assert db.get(SchemaRevision, REVISION) is not None
    with bind.connect() as connection:
        assert connection.execute(text("PRAGMA integrity_check")).scalar() == "ok"
    bind.dispose()
