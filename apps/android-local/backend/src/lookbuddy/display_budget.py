"""Global limits use delivered-display timestamps, never camera/snapshot timestamps."""
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from .models import Intervention, User, utcnow
from .preferences import preferences_for


def utc(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def lock_user(db: Session, user_id: str) -> None:
    if db.get_bind().dialect.name == "sqlite":
        db.execute(text("BEGIN IMMEDIATE"))
    else:
        db.scalar(select(User).where(User.id == user_id).with_for_update())


def next_display_at(db: Session, user_id: str, at: datetime) -> datetime:
    recent = list(db.scalars(select(Intervention).where(
        Intervention.user_id == user_id, Intervention.namespace == "live", Intervention.displayed_at.is_not(None),
        Intervention.displayed_at > at - timedelta(hours=1),
    ).order_by(Intervention.displayed_at)))
    earliest = at
    if recent:
        latest = recent[-1]
        minutes = max(15, latest.decision.get("intervalMinutes", 15))
        earliest = max(earliest, utc(latest.displayed_at) + timedelta(minutes=minutes))
    else:
        latest = db.scalar(select(Intervention).where(
            Intervention.user_id == user_id, Intervention.namespace == "live", Intervention.displayed_at.is_not(None),
        ).order_by(Intervention.displayed_at.desc()).limit(1))
        if latest:
            earliest = max(earliest, utc(latest.displayed_at) + timedelta(minutes=max(15, latest.decision.get("intervalMinutes", 15))))
    if len(recent) >= 3:
        earliest = max(earliest, utc(recent[-3].displayed_at) + timedelta(hours=1))
    silent = db.scalar(select(Intervention).where(
        Intervention.user_id == user_id, Intervention.namespace == "live", Intervention.strategy == "silent",
    ).order_by(Intervention.created_at.desc()).limit(1))
    if silent:
        earliest = max(earliest, utc(silent.created_at) + timedelta(minutes=silent.decision.get("intervalMinutes", 15)))
    return earliest


def require_display(db: Session, value: Intervention, at: datetime) -> None:
    if value.displayed_at is not None or value.namespace == "demo":
        return
    if preferences_for(db, value.user_id).busy:
        raise HTTPException(409, "Busy mode suppresses active messages")
    if not preferences_for(db, value.user_id).posture_enabled and value.category in {"posture", "sedentary"}:
        raise HTTPException(409, "Posture reminders are disabled")
    if value.category == "emotion" and not preferences_for(db, value.user_id).companion_enabled:
        raise HTTPException(409, "Companion is disabled")
    earliest = next_display_at(db, value.user_id, at)
    if earliest > at:
        raise HTTPException(409, {"reason": "display_budget", "nextAllowedAt": earliest.isoformat()})
