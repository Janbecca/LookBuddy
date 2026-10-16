"""Evidence-driven lifecycle with P2 component rewards."""
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import DomainEvent, Intervention, InterventionObservation, Reminder, StateSnapshot, utcnow
from .preferences import preferences_for
from .schemas import VisionSampleIn
from .learning import settle, settle_result

ACTIVE = ("awaiting_display", "observing", "action_started", "verifying", "monitoring", "silent_observing")
TERMINAL = ("improved", "recurred", "unknown", "interrupted", "ignored", "snoozed")
MAX_GAP_MS = 3000
RECOVERY_MS = 20_000
FOLLOWUP_MS = 120_000
RECURRENCE_MS = 60_000


def aware(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def stamp(value: datetime) -> str:
    return aware(value).isoformat()


def millis_since(now: datetime, previous: str) -> float:
    return (aware(now) - datetime.fromisoformat(previous)).total_seconds() * 1000


def owned_intervention(db: Session, user_id: str, intervention_id: str) -> Intervention:
    value = db.scalar(select(Intervention).where(Intervention.id == intervention_id, Intervention.user_id == user_id).with_for_update())
    if value is None:
        raise HTTPException(404, "Intervention not found")
    return value


def attach_reminder(db: Session, reminder: Reminder, snapshot: StateSnapshot) -> Intervention | None:
    preferences = preferences_for(db, reminder.user_id)
    selection = getattr(reminder, "_selection", None)
    # Same ID is the explicit, one-to-one legacy reminder link, with no old-table ALTER.
    intervention = Intervention(
        id=reminder.id, user_id=reminder.user_id, session_id=reminder.session_id,
        category=reminder.category, strategy=selection["strategy"] if selection else "posture_light" if reminder.category == "posture" else "short_break",
        source="web_vision", namespace="live", status="awaiting_display",
        context={"preferences": preferences.model_dump(by_alias=True, mode="json"),
                 "snapshotId": snapshot.id, "verification": {}},
        decision={**(selection or {"policyVersion": "rules-p1", "learningApplied": False}),
                  "reminderId": reminder.id, "ruleReasonCodes": reminder.reason_codes,
                  "rulesPassed": {"presence": snapshot.presence, "busy": preferences.busy,
                                  "snapshotCapturedAt": stamp(snapshot.captured_at), "displayBudgetChecked": True}},
    )
    db.add(intervention)
    if reminder.category == "emotion":
        intervention.context = {**intervention.context, "expressionBaseline": reminder._expression_baseline,
                                "expressionHypothesis": reminder._expression_label}
    if intervention.strategy == "silent":
        start_silent(intervention, utcnow())
    return intervention


def record(db: Session, value: Intervention, key: str, kind: str, at: datetime, quality: str, evidence: dict) -> None:
    db.add(InterventionObservation(
        user_id=value.user_id, intervention_id=value.id, observation_key=key,
        kind=kind, quality=quality, observed_at=at, evidence=evidence,
    ))


def outcome(db: Session, value: Intervention, state: dict, status: str, at: datetime, reason: str) -> None:
    value.status = status
    state["outcomeReason"] = reason
    state["finishedAt"] = stamp(at)
    record(db, value, f"outcome:{status}", status, at, "unavailable" if status == "unknown" else "observable", {"reason": reason})
    settle_result(db, value, status, at)
    reminder = db.get(Reminder, value.id)
    if reminder and reminder.status == "pending":
        reminder.status = "resolved"
    if value.namespace != "live":
        return  # Demo outcomes must not enter the real timeline or diary.
    db.add(DomainEvent(
        id=f"evt_{value.id}_{status}", user_id=value.user_id, session_id=value.session_id,
        type=f"intervention.{status}", occurred_at=at,
        source={"kind": value.source, "namespace": value.namespace},
        payload={"interventionId": value.id, "category": value.category, "reason": reason},
        evidence={"method": "temporal_visual_verification_p1"},
    ))


def mark_displayed(value: Intervention, at: datetime) -> None:
    if value.displayed_at is not None:
        return
    if value.status != "awaiting_display":
        raise HTTPException(409, "Intervention is not awaiting display")
    if value.namespace == "live" and value.created_at and at >= aware(value.created_at) + timedelta(minutes=10):
        raise HTTPException(409, "Intervention expired before display")
    value.displayed_at = at
    value.status = "observing"
    minutes = value.context.get("preferences", {}).get("breakMinutes", 10)
    value.due_at = at + timedelta(minutes=15 if value.category == "posture" else minutes + 10)


def start_silent(value: Intervention, at: datetime) -> None:
    value.status = "silent_observing"
    value.context = {**value.context, "observationStartedAt": stamp(at)}
    value.due_at = at + timedelta(minutes=15)


def expire_live(db: Session, user_id: str, now: datetime | None = None) -> None:
    now = now or utcnow()
    values = db.scalars(select(Intervention).where(
        Intervention.user_id == user_id, Intervention.namespace == "live", Intervention.status.in_(ACTIVE),
    ))
    for value in values:
        deadline = value.due_at or aware(value.created_at) + timedelta(minutes=10)
        if now >= aware(deadline):
            state = dict(value.context.get("verification", {}))
            outcome(db, value, state, "unknown", now, "observation_deadline_without_verified_result")
            value.context = {**value.context, "verification": state}


def sample(db: Session, value: Intervention, body: VisionSampleIn) -> bool:
    """Returns False for an exact retry. Caller commits state and evidence atomically."""
    payload = body.model_dump(by_alias=True, mode="json")
    duplicate = db.scalar(select(InterventionObservation).where(
        InterventionObservation.intervention_id == value.id,
        InterventionObservation.observation_key == f"frame:{body.frame_id}",
    ))
    if duplicate:
        if duplicate.evidence != payload:
            raise HTTPException(409, "Frame ID reused with different evidence")
        return False
    anchor = value.displayed_at or (datetime.fromisoformat(value.context["observationStartedAt"]) if value.context.get("observationStartedAt") else None)
    if value.status not in (*ACTIVE, "snoozed") or anchor is None:
        raise HTTPException(409, "Intervention is not accepting observations")
    if value.category not in {"posture", "sedentary"}:
        raise HTTPException(422, "Action verification is not yet available for this category")
    if value.namespace == "live" and body.source != "webcam":
        raise HTTPException(422, "Simulated/replay evidence must use a demo intervention")
    at = body.captured_at.astimezone(timezone.utc)
    if at < aware(anchor):
        raise HTTPException(422, "Observation predates intervention display")
    if value.namespace == "live" and at > utcnow() + timedelta(seconds=5):
        raise HTTPException(422, "Future observations are only allowed in demo")
    state = dict(value.context.get("verification", {}))
    if state.get("lastAt") and millis_since(at, state["lastAt"]) <= 0:
        raise HTTPException(422, "Observation times must increase")
    record(db, value, f"frame:{body.frame_id}", "vision_sample", at, body.quality, payload)
    gap = bool(state.get("lastAt") and millis_since(at, state["lastAt"]) > MAX_GAP_MS)
    state["lastAt"] = stamp(at)
    if value.due_at and at >= aware(value.due_at):
        outcome(db, value, state, "unknown", at, "observation_deadline")
    elif value.category == "posture":
        _posture(db, value, state, body, at, gap)
    elif value.category == "sedentary":
        _break(db, value, state, body, at, gap)
    value.context = {**value.context, "verification": state}
    return True


def _posture(db: Session, value: Intervention, state: dict, body: VisionSampleIn, at: datetime, gap: bool) -> None:
    valid = body.camera_healthy and body.quality == "observable" and body.posture != "unknown" and body.presence != "away"
    if gap or not valid:
        state.pop("normalSince", None)
        state.pop("badSince", None)
        if value.status == "monitoring":
            outcome(db, value, state, "unknown", at, "followup_not_continuously_observable")
        return
    if value.status == "monitoring":
        if body.posture == "bad":
            state.setdefault("badSince", stamp(at))
            if millis_since(at, state["badSince"]) >= RECURRENCE_MS:
                outcome(db, value, state, "recurred", at, "bad_posture_repeated_60s")
                return
        else:
            state.pop("badSince", None)
        if millis_since(at, state["completedAt"]) >= FOLLOWUP_MS:
            # End-of-window bad posture needs enough evidence to classify recurrence.
            if body.posture == "bad":
                outcome(db, value, state, "unknown", at, "end_of_followup_abnormal_but_unconfirmed")
            else:
                outcome(db, value, state, "improved", at, "normal_20s_and_observable_followup_120s")
        return
    if body.posture == "bad":
        state.pop("normalSince", None)
        value.status = "observing"
        return
    state.setdefault("normalSince", stamp(at))
    if not state.get("startedAt"):
        state["startedAt"] = stamp(at)
        record(db, value, "action:started", "action_started", at, "observable", {"method": "normal_posture_observed"})
    value.status = "verifying"
    if millis_since(at, state["normalSince"]) >= RECOVERY_MS:
        state["completedAt"] = stamp(at)
        value.status = "monitoring"
        record(db, value, "action:completed", "action_completed", at, "observable", {"normalHoldMs": RECOVERY_MS})
        settle(db, value, "completed", 0.25, at)


def _break(db: Session, value: Intervention, state: dict, body: VisionSampleIn, at: datetime, gap: bool) -> None:
    if gap or not body.camera_healthy:
        state.pop("stoodAt", None)
        if state.get("awaySince"):
            outcome(db, value, state, "unknown", at, "camera_or_timing_gap_during_break")
        return
    if body.presence == "standing" and body.quality == "observable" and body.standing_confidence >= 0.8:
        state["stoodAt"] = stamp(at)
        if not state.get("startedAt"):
            state["startedAt"] = stamp(at)
            record(db, value, "action:started", "action_started", at, "observable", {"method": "verified_standing_transition"})
        value.status = "action_started"
    elif body.presence == "away":
        if not state.get("awaySince"):
            if not state.get("stoodAt") or millis_since(at, state["stoodAt"]) > 15_000:
                outcome(db, value, state, "unknown", at, "left_frame_without_verified_standing")
                return
            state["awaySince"] = stamp(at)
            value.status = "verifying"
        target_ms = value.context.get("preferences", {}).get("breakMinutes", 10) * 60_000
        if millis_since(at, state["awaySince"]) >= target_ms:
            record(db, value, "action:completed", "action_completed", at, "observable", {"awayDurationMs": target_ms})
            settle(db, value, "completed", 0.25, at)
            state["completedAt"] = stamp(at)
            outcome(db, value, state, "improved", at, "verified_standing_and_away_duration_met")
    elif body.presence in {"present", "seated"}:
        state.pop("stoodAt", None)
        if state.get("awaySince"):
            outcome(db, value, state, "interrupted", at, "returned_before_break_target")
    else:
        state.pop("stoodAt", None)
        if state.get("awaySince"):
            outcome(db, value, state, "unknown", at, "presence_unobservable_during_break")
