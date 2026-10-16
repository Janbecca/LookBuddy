from datetime import date, datetime, timedelta, timezone
from random import Random

import jwt
from fastapi import APIRouter, Depends, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from sqlalchemy import delete, desc, select
from sqlalchemy.orm import Session

from .config import get_settings
from .database import get_db, SessionLocal
from .diary_service import generate_diary
from .models import (
    Device,
    Diary,
    DomainEvent,
    RefreshToken,
    Reminder,
    ReminderResponse,
    StateSnapshot,
    User,
    UserPreferences,
    Intervention,
    InterventionObservation,
    PolicyState,
    RewardLedger,
    ScheduledIntervention,
    ExpressionObservation,
    LocalCapture,
    EmotionCorrection,
    WorkSession,
    utcnow,
)
from .realtime import manager
from .reminder_policy import ReminderPolicy
from .preferences import preferences_for
from .action_service import ACTIVE, attach_reminder, expire_live, mark_displayed, owned_intervention, record, sample, start_silent
from .display_budget import lock_user, require_display, next_display_at
from .learning import choose, settle_response
from .scheduler import dispatch, snooze
from .emotion_service import ingest as ingest_expression, observe as observe_expression, candidate as emotion_candidate
from .schemas import (
    DeviceCreate,
    DevicePublic,
    DiaryPublic,
    DiaryUpdate,
    EventBatchRequest,
    EventBatchResponse,
    LoginRequest,
    LogoutRequest,
    RefreshRequest,
    RegisterRequest,
    ReminderPublic,
    ReminderRespondRequest,
    SessionCreate,
    SessionPublic,
    SnapshotResponse,
    StateSnapshotIn,
    TokenPair,
    UserPublic,
    PreferencesPublic,
    PreferencesPatch,
    InterventionPublic,
    VisionSampleIn,
    DemoInterventionIn,
    ObservationPublic,
    ExpressionIn,
    ExpressionPublic,
    LocalCaptureIn,
    EmotionCorrectionIn,
    EmotionLabel,
)
from .security import (
    create_access_token,
    digest_refresh_token,
    get_current_user,
    hash_password,
    load_valid_refresh_token,
    new_refresh_token,
    store_refresh_token,
    verify_password,
)


router = APIRouter(prefix="/v1")


def issue_tokens(db: Session, user: User) -> TokenPair:
    refresh = new_refresh_token()
    store_refresh_token(db, user.id, refresh)
    db.commit()
    return TokenPair(
        access_token=create_access_token(user.id),
        refresh_token=refresh,
        user=UserPublic.model_validate(user),
    )


@router.post("/auth/register", response_model=TokenPair, status_code=status.HTTP_201_CREATED)
def register(body: RegisterRequest, db: Session = Depends(get_db)) -> TokenPair:
    email = body.email.lower()
    if db.scalar(select(User).where(User.email == email)):
        raise HTTPException(status_code=409, detail="Email is already registered")
    user = User(email=email, password_hash=hash_password(body.password), display_name=body.display_name, timezone=body.timezone)
    db.add(user)
    db.flush()
    return issue_tokens(db, user)


@router.post("/auth/login", response_model=TokenPair)
def login(body: LoginRequest, db: Session = Depends(get_db)) -> TokenPair:
    user = db.scalar(select(User).where(User.email == body.email.lower()))
    if user is None or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid email or password")
    return issue_tokens(db, user)


@router.post("/auth/refresh", response_model=TokenPair)
def refresh(body: RefreshRequest, db: Session = Depends(get_db)) -> TokenPair:
    stored = load_valid_refresh_token(db, body.refresh_token)
    if stored is None:
        raise HTTPException(status_code=401, detail="Invalid or expired refresh token")
    user = db.get(User, stored.user_id)
    if user is None or not user.is_active:
        raise HTTPException(status_code=401, detail="Account is unavailable")
    stored.revoked_at = utcnow()
    return issue_tokens(db, user)


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
def logout(body: LogoutRequest, db: Session = Depends(get_db)) -> None:
    stored = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == digest_refresh_token(body.refresh_token)))
    if stored and stored.revoked_at is None:
        stored.revoked_at = utcnow()
        db.commit()


@router.get("/me", response_model=UserPublic)
def me(user: User = Depends(get_current_user)) -> User:
    return user


@router.get("/preferences", response_model=PreferencesPublic)
def get_preferences(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> PreferencesPublic:
    return preferences_for(db, user.id)


@router.patch("/preferences", response_model=PreferencesPublic)
async def update_preferences(
    body: PreferencesPatch, user: User = Depends(get_current_user), db: Session = Depends(get_db)
) -> PreferencesPublic:
    lock_user(db, user.id)
    stored = db.get(UserPreferences, user.id)
    if stored is None:
        stored = UserPreferences(user_id=user.id)
        db.add(stored)
    for key, value in body.model_dump(exclude_unset=True).items():
        setattr(stored, key, value)
    db.commit()
    db.refresh(stored)
    result = PreferencesPublic.model_validate(stored)
    await manager.publish(user.id, {"type": "preferences.updated", "data": result.model_dump(by_alias=True, mode="json")})
    return result


@router.delete("/account", status_code=status.HTTP_204_NO_CONTENT)
def delete_account(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> None:
    db.delete(user)
    db.commit()


@router.post("/devices", response_model=DevicePublic, status_code=status.HTTP_201_CREATED)
def create_device(body: DeviceCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> Device:
    device = Device(user_id=user.id, **body.model_dump())
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@router.get("/devices", response_model=list[DevicePublic])
def list_devices(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[Device]:
    return list(db.scalars(select(Device).where(Device.user_id == user.id).order_by(Device.created_at)))


@router.post("/sessions", response_model=SessionPublic, status_code=status.HTTP_201_CREATED)
def create_session(body: SessionCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> WorkSession:
    if body.device_id:
        device = db.scalar(select(Device).where(Device.id == body.device_id, Device.user_id == user.id))
        if device is None:
            raise HTTPException(status_code=404, detail="Device not found")
    work_session = WorkSession(user_id=user.id, device_id=body.device_id)
    db.add(work_session)
    db.commit()
    db.refresh(work_session)
    return work_session


@router.post("/sessions/{session_id}/finish", response_model=SessionPublic)
def finish_session(session_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> WorkSession:
    lock_user(db, user.id)
    work_session = owned_session(db, user.id, session_id)
    if work_session.status == "finished":
        return work_session
    work_session.status = "finished"
    work_session.ended_at = utcnow()
    db.add(DomainEvent(id=f"evt_work_{work_session.id}", user_id=user.id, session_id=session_id,
        type="work.session_completed", occurred_at=work_session.ended_at,
        duration_ms=max(0, int((work_session.ended_at - work_session.started_at.replace(tzinfo=timezone.utc)).total_seconds() * 1000)),
        source={"kind": "work_session", "namespace": "live"}, payload={}))
    from .action_service import outcome
    for intervention in db.scalars(select(Intervention).where(Intervention.session_id == session_id, Intervention.status.in_((*ACTIVE, "snoozed")))):
        state = dict(intervention.context.get("verification", {}))
        outcome(db, intervention, state, "unknown", utcnow(), "session_finished")
        intervention.context = {**intervention.context, "verification": state}
    for job in db.scalars(select(ScheduledIntervention).join(Intervention, Intervention.id == ScheduledIntervention.intervention_id).where(Intervention.session_id == session_id, ScheduledIntervention.status == "pending")):
        job.status, job.reason = "cancelled", "session_finished"
    db.commit()
    db.refresh(work_session)
    return work_session


@router.get("/sessions/{session_id}", response_model=SessionPublic)
def get_session(session_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> WorkSession:
    return owned_session(db, user.id, session_id)


def owned_session(db: Session, user_id: str, session_id: str) -> WorkSession:
    work_session = db.scalar(select(WorkSession).where(WorkSession.id == session_id, WorkSession.user_id == user_id))
    if work_session is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return work_session


@router.post("/events/batch", response_model=EventBatchResponse)
def ingest_events(body: EventBatchRequest, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> EventBatchResponse:
    accepted = 0
    duplicates = 0
    for item in body.events:
        if item.session_id:
            owned_session(db, user.id, item.session_id)
        if item.device_id:
            device = db.scalar(select(Device).where(Device.id == item.device_id, Device.user_id == user.id))
            if device is None:
                raise HTTPException(status_code=404, detail=f"Device not found: {item.device_id}")
        duplicate_by_seq = None
        if item.device_id is not None and item.seq is not None:
            duplicate_by_seq = db.scalar(
                select(DomainEvent).where(DomainEvent.device_id == item.device_id, DomainEvent.seq == item.seq)
            )
        if db.get(DomainEvent, item.event_id) or duplicate_by_seq:
            duplicates += 1
            continue
        event = DomainEvent(
            id=item.event_id,
            user_id=user.id,
            device_id=item.device_id,
            session_id=item.session_id,
            seq=item.seq,
            type=item.type,
            occurred_at=item.occurred_at.astimezone(timezone.utc),
            source=item.source.model_dump(by_alias=True, exclude_none=True),
            confidence=item.confidence,
            duration_ms=item.duration_ms,
            payload=item.payload,
            evidence=item.evidence.model_dump(by_alias=True, exclude_none=True) if item.evidence else {},
            privacy=item.privacy.model_dump(by_alias=True),
        )
        db.add(event)
        db.flush()
        accepted += 1
    db.commit()
    return EventBatchResponse(accepted=accepted, duplicates=duplicates)


@router.get("/timeline")
def timeline(
    limit: int = Query(default=100, ge=1, le=500),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> list[dict]:
    events = db.scalars(
        select(DomainEvent).where(DomainEvent.user_id == user.id).order_by(desc(DomainEvent.occurred_at)).limit(limit)
    ).all()
    return [
        {
            "eventId": event.id,
            "type": event.type,
            "occurredAt": event.occurred_at,
            "confidence": event.confidence,
            "durationMs": event.duration_ms,
            "payload": event.payload,
            "source": event.source,
        }
        for event in events
    ]


@router.post("/state-snapshots", response_model=SnapshotResponse)
async def submit_snapshot(body: StateSnapshotIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> SnapshotResponse:
    lock_user(db, user.id)
    if body.captured_at.tzinfo is None or abs((utcnow() - body.captured_at).total_seconds()) > 30:
        raise HTTPException(422, "Live snapshots require a current timezone-aware timestamp; use demo replay for acceleration")
    if owned_session(db, user.id, body.session_id).status != "active":
        raise HTTPException(409, "Session is finished")
    snapshot_data = body.model_dump()
    snapshot_data["captured_at"] = body.captured_at.astimezone(timezone.utc)
    if body.last_drink_at is not None:
        snapshot_data["last_drink_at"] = body.last_drink_at.astimezone(timezone.utc)
    snapshot = StateSnapshot(user_id=user.id, **snapshot_data)
    db.add(snapshot)
    db.flush()
    dispatch(db, user.id, utcnow())
    reminder = ReminderPolicy().decide(db, snapshot)
    intervention = None
    if reminder:
        db.add(reminder)
        db.flush()
        intervention = attach_reminder(db, reminder, snapshot)
    db.commit()
    if reminder and reminder.status != "silent":
        db.refresh(reminder)
        await manager.publish(user.id, {"type": "reminder.created", "data": ReminderPublic.model_validate(reminder).model_dump(by_alias=True, mode="json")})
    return SnapshotResponse(reminder=reminder if reminder and reminder.status != "silent" else None, intervention=intervention)


@router.get("/reminders/pending", response_model=list[ReminderPublic])
def pending_reminders(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[Reminder]:
    now = datetime.now(timezone.utc)
    lock_user(db, user.id)
    dispatch(db, user.id, now)
    db.commit()
    return list(
        db.scalars(
            select(Reminder)
            .where(Reminder.user_id == user.id, Reminder.status == "pending", Reminder.expires_at > now)
            .order_by(desc(Reminder.priority), Reminder.created_at)
        )
    )


@router.post("/reminders/{reminder_id}/respond", response_model=ReminderPublic)
def respond_reminder(
    reminder_id: str,
    body: ReminderRespondRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
) -> Reminder:
    lock_user(db, user.id)
    snooze_minutes = (body.snooze_minutes or 10) if body.action == "snoozed" else None
    reminder = db.scalar(select(Reminder).where(Reminder.id == reminder_id, Reminder.user_id == user.id))
    if reminder is None:
        raise HTTPException(status_code=404, detail="Reminder not found")
    if reminder.status != "pending":
        previous = db.scalar(select(ReminderResponse).where(ReminderResponse.reminder_id == reminder.id).order_by(desc(ReminderResponse.created_at)))
        if previous and previous.action == body.action and previous.snooze_minutes == snooze_minutes:
            return reminder
        raise HTTPException(status_code=409, detail="Reminder already handled")
    if utcnow() >= reminder.expires_at.replace(tzinfo=timezone.utc):
        raise HTTPException(status_code=409, detail="Reminder expired")
    reminder.status = body.action
    reminder.responded_at = utcnow()
    db.add(ReminderResponse(reminder_id=reminder.id, user_id=user.id, action=body.action, snooze_minutes=snooze_minutes))
    intervention = db.get(Intervention, reminder.id)
    if intervention:
        require_display(db, intervention, utcnow())
        mark_displayed(intervention, utcnow())
        if body.action == "accepted":
            pass
        elif intervention.status in ACTIVE:
            intervention.status = body.action
        record(db, intervention, "response:user", body.action, utcnow(), "observable", {"snoozeMinutes": snooze_minutes})
        settle_response(db, intervention, body.action, utcnow())
        if body.action == "snoozed" and intervention.status == "snoozed":
            snooze(db, intervention, snooze_minutes, utcnow())
    db.commit()
    db.refresh(reminder)
    return reminder


@router.get("/interventions", response_model=list[InterventionPublic])
def list_interventions(
    namespace: str = Query(default="live", pattern="^(live|demo)$"),
    limit: int = Query(default=50, ge=1, le=200),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> list[Intervention]:
    lock_user(db, user.id)
    expire_live(db, user.id)
    db.commit()
    return list(db.scalars(select(Intervention).where(
        Intervention.user_id == user.id, Intervention.namespace == namespace,
    ).order_by(desc(Intervention.created_at)).limit(limit)))


@router.get("/interventions/{intervention_id}", response_model=InterventionPublic)
def get_intervention(intervention_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> Intervention:
    lock_user(db, user.id)
    expire_live(db, user.id)
    db.commit()
    return owned_intervention(db, user.id, intervention_id)


@router.post("/interventions/{intervention_id}/displayed", response_model=InterventionPublic)
def display_intervention(intervention_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> Intervention:
    lock_user(db, user.id)
    value = owned_intervention(db, user.id, intervention_id)
    if owned_session(db, user.id, value.session_id).status != "active":
        raise HTTPException(409, "Session is finished")
    require_display(db, value, utcnow())
    mark_displayed(value, utcnow())
    db.commit()
    db.refresh(value)
    return value


@router.post("/interventions/{intervention_id}/samples")
async def submit_vision_sample(
    intervention_id: str, body: VisionSampleIn, user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> dict:
    lock_user(db, user.id)
    value = owned_intervention(db, user.id, intervention_id)
    if owned_session(db, user.id, value.session_id).status != "active":
        raise HTTPException(409, "Session is finished")
    accepted = sample(db, value, body)
    db.commit()
    db.refresh(value)
    result = InterventionPublic.model_validate(value).model_dump(by_alias=True, mode="json")
    if accepted:
        await manager.publish(user.id, {"type": "intervention.updated", "data": result})
    return {"accepted": accepted, "intervention": result}


@router.get("/interventions/{intervention_id}/observations", response_model=list[ObservationPublic])
def intervention_observations(
    intervention_id: str, limit: int = Query(default=200, ge=1, le=1000),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> list[InterventionObservation]:
    owned_intervention(db, user.id, intervention_id)
    return list(db.scalars(select(InterventionObservation).where(
        InterventionObservation.intervention_id == intervention_id,
    ).order_by(desc(InterventionObservation.observed_at), desc(InterventionObservation.id)).limit(limit)))


@router.post("/debug/interventions", response_model=InterventionPublic, status_code=201)
def create_demo_intervention(
    body: DemoInterventionIn, user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> Intervention:
    lock_user(db, user.id)
    if owned_session(db, user.id, body.session_id).status != "active":
        raise HTTPException(409, "Session is finished")
    preferences = preferences_for(db, user.id)
    demo_at = utcnow()
    if body.category == "emotion":
        latest_expression = db.scalar(select(ExpressionObservation).where(
            ExpressionObservation.user_id == user.id, ExpressionObservation.session_id == body.session_id,
            ExpressionObservation.namespace == "demo",
        ).order_by(ExpressionObservation.captured_at.desc()).limit(1))
        if latest_expression:
            demo_at = max(demo_at, latest_expression.captured_at.replace(tzinfo=timezone.utc))
    expression = emotion_candidate(db, user.id, body.session_id, "demo", demo_at) if body.category == "emotion" else None
    if body.category == "emotion" and expression is None:
        raise HTTPException(422, "Demo emotion intervention requires a fresh 30s observable expression baseline")
    value = Intervention(
        user_id=user.id, session_id=body.session_id, namespace="demo", category=body.category,
        strategy="emotion_inquiry" if body.category == "emotion" else "posture_light" if body.category == "posture" else "short_break",
        status="awaiting_display", source="simulated", created_at=demo_at,
        context={"preferences": preferences.model_dump(by_alias=True, mode="json"), "verification": {}},
        decision={"policyVersion": "demo-p1", "learningApplied": False},
    )
    if expression:
        value.context = {**value.context, "expressionBaseline": expression["baseline"], "expressionHypothesis": expression["label"]}
    if body.learning:
        decision = choose(db, user.id, "demo", body.category, demo_at, rng=Random(body.seed) if body.seed is not None else None,
                          visual_state=expression["label"] if expression else None)
        value.decision, value.strategy = decision, decision["strategy"]
    if value.strategy == "silent":
        start_silent(value, demo_at)
    else:
        mark_displayed(value, demo_at)
    db.add(value)
    db.commit()
    db.refresh(value)
    return value


@router.get("/learning/preview")
def learning_preview(
    category: str = Query(default="posture", pattern="^(posture|sedentary|drink|fatigue|emotion)$"),
    namespace: str = Query(default="live", pattern="^(live|demo)$"),
    visual_state: EmotionLabel | None = Query(default=None, alias="visualState"),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> dict:
    now = utcnow()
    return {"decision": choose(db, user.id, namespace, category, now, visual_state=visual_state),
            "previewOnly": True, "nextAllowedAt": next_display_at(db, user.id, now).isoformat(),
            "busy": preferences_for(db, user.id).busy}


@router.post("/debug/interventions/{intervention_id}/respond", response_model=InterventionPublic)
def demo_response(
    intervention_id: str, body: ReminderRespondRequest, user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> Intervention:
    lock_user(db, user.id)
    value = owned_intervention(db, user.id, intervention_id)
    if value.namespace != "demo":
        raise HTTPException(422, "Debug responses cannot modify live interventions")
    evidence = {"action": body.action, "snoozeMinutes": body.snooze_minutes}
    previous = db.scalar(select(InterventionObservation).where(
        InterventionObservation.intervention_id == value.id, InterventionObservation.observation_key == "response:demo",
    ))
    if previous:
        if previous.evidence != evidence:
            raise HTTPException(409, "Demo response already handled")
        return value
    if value.status not in ACTIVE:
        raise HTTPException(409, "Demo intervention is finished")
    if value.strategy == "silent":
        from .action_service import outcome
        state = dict(value.context.get("verification", {}))
        outcome(db, value, state, "unknown", utcnow(), "silent_strategy_has_no_message_to_respond_to")
        value.context = {**value.context, "verification": state}
        db.commit()
        db.refresh(value)
        return value
    record(db, value, "response:demo", body.action, utcnow(), "observable", evidence)
    if body.action != "accepted":
        value.status = body.action
    settle_response(db, value, body.action, utcnow())
    db.commit()
    db.refresh(value)
    return value


@router.get("/learning/states")
def learning_states(
    namespace: str = Query(default="live", pattern="^(live|demo)$"),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> list[dict]:
    return [{"contextKey": row.context_key, "arm": row.arm, "parameters": row.parameters} for row in db.scalars(
        select(PolicyState).where(PolicyState.user_id == user.id, PolicyState.namespace == namespace).order_by(PolicyState.context_key, PolicyState.arm))]


@router.get("/learning/config")
def learning_config(user: User = Depends(get_current_user)) -> dict:
    from .learning import POLICY_VERSION, REWARD_VERSION, INTERVALS, STRATEGIES
    return {"policyVersion": POLICY_VERSION, "rewardVersion": REWARD_VERSION,
            "intervals": INTERVALS, "strategies": STRATEGIES, "halfLifeDays": 7,
            "minimumIntervalMinutes": 15, "rollingHourlyLimit": 3,
            "weights": {"completed": 0.25, "sustained": 0.75, "recurrence": -0.25,
                        "accepted": 0.05, "ignored": -0.6, "snoozedTimingOnly": -0.1,
                        "knownOutcomeDisplayCost": -0.05},
            "expression": {"rewardVersion": "expression-v1", "visibleShift": 0.1, "displayCost": -0.01,
                           "possibleBurden": -0.05, "consistentEpisodesRequired": 3,
                           "baselineSeconds": 30, "immediateSeconds": 30, "followupSeconds": 120}}


@router.post("/debug/learning/reset")
def reset_demo_learning(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    lock_user(db, user.id)
    # Explicitly scoped to this user's demo records. All dependent demo evidence cascades.
    db.execute(delete(Intervention).where(Intervention.user_id == user.id, Intervention.namespace == "demo"))
    db.execute(delete(ExpressionObservation).where(ExpressionObservation.user_id == user.id, ExpressionObservation.namespace == "demo"))
    db.execute(delete(PolicyState).where(PolicyState.user_id == user.id, PolicyState.namespace == "demo"))
    db.commit()
    return {"namespace": "demo", "reset": True}


@router.post("/expressions")
async def submit_expression(body: ExpressionIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    lock_user(db, user.id)
    if owned_session(db, user.id, body.session_id).status != "active":
        raise HTTPException(409, "Session is finished")
    expression, accepted = ingest_expression(db, user.id, body)
    updated = None
    if accepted:
        expire_live(db, user.id)
        updated = db.scalar(select(Intervention).where(
            Intervention.user_id == user.id, Intervention.session_id == body.session_id,
            Intervention.namespace == body.namespace, Intervention.category == "emotion",
            Intervention.status.in_((*ACTIVE, "snoozed")),
        ).order_by(Intervention.created_at.desc()).limit(1))
        if updated:
            observe_expression(db, updated, expression)
    db.commit()
    db.refresh(expression)
    result = {"accepted": accepted, "observation": ExpressionPublic.model_validate(expression).model_dump(by_alias=True, mode="json"),
              "intervention": InterventionPublic.model_validate(updated).model_dump(by_alias=True, mode="json") if updated else None}
    if updated:
        await manager.publish(user.id, {"type": "intervention.updated", "data": result["intervention"]})
    return result


@router.get("/expressions", response_model=list[ExpressionPublic])
def list_expressions(
    namespace: str = Query(default="live", pattern="^(live|demo)$"), limit: int = Query(default=100, ge=1, le=500),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> list[ExpressionObservation]:
    return list(db.scalars(select(ExpressionObservation).where(
        ExpressionObservation.user_id == user.id, ExpressionObservation.namespace == namespace,
    ).order_by(ExpressionObservation.captured_at.desc()).limit(limit)))


@router.post("/emotions/captures", status_code=201)
def register_local_capture(body: LocalCaptureIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    lock_user(db, user.id)
    expression = db.scalar(select(ExpressionObservation).where(ExpressionObservation.id == body.expression_id, ExpressionObservation.user_id == user.id))
    if expression is None:
        raise HTTPException(404, "Expression not found")
    if abs((body.captured_at - expression.captured_at.replace(tzinfo=timezone.utc)).total_seconds()) > 5:
        raise HTTPException(422, "Local capture must refer to an observation within 5s")
    previous = db.get(LocalCapture, body.capture_id)
    if previous:
        if previous.user_id != user.id or previous.expression_id != body.expression_id or previous.captured_at.replace(tzinfo=timezone.utc) != body.captured_at:
            raise HTTPException(409, "Capture ID reused")
    else:
        db.add(LocalCapture(id=body.capture_id, user_id=user.id, expression_id=expression.id, captured_at=body.captured_at.astimezone(timezone.utc)))
        db.commit()
    return {"captureId": body.capture_id, "expressionId": expression.id, "suggestedLabel": expression.suggested_label,
            "imageStored": False, "imageUploaded": False}


@router.post("/emotions/corrections")
def correct_emotion(body: EmotionCorrectionIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> dict:
    lock_user(db, user.id)
    capture = db.scalar(select(LocalCapture).where(LocalCapture.id == body.capture_id, LocalCapture.user_id == user.id))
    if capture is None:
        raise HTTPException(404, "Capture not found")
    expression = db.get(ExpressionObservation, capture.expression_id)
    if body.suggested_label != expression.suggested_label:
        raise HTTPException(409, "Original hypothesis must match; correction never rewrites it")
    previous = db.get(EmotionCorrection, body.correction_id)
    if previous:
        if previous.user_id != user.id or previous.capture_id != capture.id or previous.confirmed_label != body.confirmed_label:
            raise HTTPException(409, "Correction ID reused")
    else:
        db.add(EmotionCorrection(id=body.correction_id, user_id=user.id, capture_id=capture.id,
                                suggested_label=expression.suggested_label, confirmed_label=body.confirmed_label))
        db.commit()
    return {"captureId": capture.id, "suggestedLabel": expression.suggested_label, "confirmedLabel": body.confirmed_label,
            "source": "user_self_report", "usedAsSatisfactionReward": False}


@router.get("/emotions/corrections")
def list_emotion_corrections(
    namespace: str = Query(default="live", pattern="^(live|demo)$"), user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> list[dict]:
    values = db.scalars(select(EmotionCorrection).join(LocalCapture, EmotionCorrection.capture_id == LocalCapture.id)
                       .join(ExpressionObservation, LocalCapture.expression_id == ExpressionObservation.id).where(
        EmotionCorrection.user_id == user.id, ExpressionObservation.namespace == namespace,
    ).order_by(EmotionCorrection.created_at.desc()).limit(100))
    return [{"correctionId": row.id, "captureId": row.capture_id, "suggestedLabel": row.suggested_label,
             "confirmedLabel": row.confirmed_label, "source": "user_self_report"} for row in values]


@router.get("/learning/rewards")
def learning_rewards(
    namespace: str = Query(default="live", pattern="^(live|demo)$"), limit: int = Query(default=100, ge=1, le=500),
    user: User = Depends(get_current_user), db: Session = Depends(get_db),
) -> list[dict]:
    return [{"interventionId": row.intervention_id, "key": row.settlement_key, "amount": row.amount,
             "version": row.version, "components": row.components} for row in db.scalars(
        select(RewardLedger).join(Intervention, RewardLedger.intervention_id == Intervention.id).where(
            RewardLedger.user_id == user.id, Intervention.namespace == namespace,
        ).order_by(RewardLedger.created_at.desc()).limit(limit))]


@router.get("/scheduler/jobs")
def scheduled_jobs(user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> list[dict]:
    from .action_service import stamp
    return [{"id": job.id, "namespace": job.namespace, "interventionId": job.intervention_id, "status": job.status, "dueAt": stamp(job.due_at),
             "reason": job.reason, "dispatchedId": job.dispatched_id} for job in db.scalars(
        select(ScheduledIntervention).where(ScheduledIntervention.user_id == user.id).order_by(ScheduledIntervention.due_at.desc()).limit(100))]


@router.post("/diaries/{diary_date}/generate", response_model=DiaryPublic)
async def create_diary(diary_date: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> Diary:
    return await generate_diary(db, user, diary_date)


@router.get("/diaries/{diary_date}", response_model=DiaryPublic)
def get_diary(diary_date: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> Diary:
    diary = db.scalar(select(Diary).where(Diary.user_id == user.id, Diary.diary_date == diary_date))
    if diary is None:
        raise HTTPException(status_code=404, detail="Diary not found")
    return diary


@router.patch("/diaries/{diary_date}", response_model=DiaryPublic)
def update_diary(diary_date: date, body: DiaryUpdate, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> Diary:
    diary = db.scalar(select(Diary).where(Diary.user_id == user.id, Diary.diary_date == diary_date))
    if diary is None:
        raise HTTPException(status_code=404, detail="Diary not found")
    diary.content = body.content
    diary.status = "edited"
    diary.updated_at = utcnow()
    db.commit()
    db.refresh(diary)
    return diary


@router.delete("/diaries/{diary_date}", status_code=status.HTTP_204_NO_CONTENT)
def delete_diary(diary_date: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)) -> None:
    result = db.execute(delete(Diary).where(Diary.user_id == user.id, Diary.diary_date == diary_date))
    db.commit()
    if not result.rowcount:
        raise HTTPException(status_code=404, detail="Diary not found")


@router.websocket("/stream")
async def realtime_stream(websocket: WebSocket, token: str | None = Query(default=None)) -> None:
    authorization = websocket.headers.get("authorization", "")
    if authorization.startswith("Bearer "):
        token = authorization[7:]
    try:
        if not token:
            raise ValueError
        payload = jwt.decode(token, get_settings().jwt_secret, algorithms=["HS256"])
        if payload.get("type") != "access" or not payload.get("sub"):
            raise ValueError
        user_id = str(payload["sub"])
        with SessionLocal() as db:
            if db.get(User, user_id) is None:
                raise ValueError
    except (jwt.InvalidTokenError, ValueError):
        await websocket.close(code=4401)
        return
    await manager.connect(user_id, websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(user_id, websocket)

