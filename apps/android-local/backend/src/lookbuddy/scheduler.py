import asyncio
import logging
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from .action_service import ACTIVE, aware, expire_live
from .database import SessionLocal
from .display_budget import lock_user, next_display_at
from .models import Intervention, Reminder, ScheduledIntervention, StateSnapshot, WorkSession, utcnow
from .preferences import preferences_for
from .emotion_service import candidate as emotion_candidate

logger = logging.getLogger(__name__)


def snooze(db: Session, value: Intervention, minutes: int, at: datetime) -> None:
    db.add(ScheduledIntervention(
        user_id=value.user_id, intervention_id=value.id, namespace=value.namespace,
        due_at=at + timedelta(minutes=minutes), reason="user_snoozed",
    ))
    value.due_at = at + timedelta(hours=24)


def dispatch(db: Session, user_id: str, at: datetime) -> list[str]:
    """Locked per user by caller. Persisted jobs survive restarts; no process timers."""
    expire_live(db, user_id, at)
    db.flush()
    delivered = []
    jobs = list(db.scalars(select(ScheduledIntervention).where(
        ScheduledIntervention.user_id == user_id, ScheduledIntervention.namespace == "live",
        ScheduledIntervention.status == "pending", ScheduledIntervention.due_at <= at,
    ).order_by(ScheduledIntervention.due_at)))
    for job in jobs:
        root = db.get(Intervention, job.intervention_id)
        session = db.get(WorkSession, root.session_id)
        preferences = preferences_for(db, user_id)
        if session.status != "active" or root.status in {"improved", "recurred", "interrupted", "unknown", "ignored", "expression_relaxed", "expression_tense", "expression_unchanged"} or root.context.get("verification", {}).get("completedAt"):
            job.status, job.reason = "cancelled", "session_or_action_finished"
            continue
        if at - aware(root.created_at) > timedelta(hours=24):
            job.status, job.reason = "cancelled", "reschedule_expired"
            continue
        if root.status in {"verifying", "action_started"}:
            job.due_at, job.reason = at + timedelta(seconds=30), "action_in_progress"
            continue
        if not preferences.posture_enabled and root.category in {"posture", "sedentary"}:
            job.status, job.reason = "cancelled", "feature_disabled"
            root.status = "cancelled"
            continue
        if root.category == "emotion" and not preferences.companion_enabled:
            job.status, job.reason, root.status = "cancelled", "companion_disabled", "cancelled"
            continue
        earliest = next_display_at(db, user_id, at)
        if preferences.busy or earliest > at:
            job.due_at, job.reason = max(earliest, at + timedelta(minutes=1)), "busy_or_budget"
            continue
        other = db.scalar(select(Intervention.id).where(
            Intervention.user_id == user_id, Intervention.namespace == "live", Intervention.id != root.id,
            Intervention.status.in_(ACTIVE),
        ).limit(1))
        latest = db.scalar(select(StateSnapshot).where(StateSnapshot.user_id == user_id, StateSnapshot.session_id == root.session_id).order_by(StateSnapshot.captured_at.desc()).limit(1))
        if other or latest is None or latest.presence != "online" or not timedelta(0) <= at - aware(latest.captured_at) <= timedelta(seconds=30):
            job.due_at, job.reason = at + timedelta(seconds=15), "waiting_for_fresh_online_context"
            continue
        from .reminder_policy import ReminderPolicy
        expression = emotion_candidate(db, user_id, root.session_id, "live", at) if root.category == "emotion" else None
        still_triggered = bool(expression) if root.category == "emotion" else root.category in {candidate.category for candidate in ReminderPolicy()._candidates(latest, at, preferences.sedentary_minutes)}
        if not still_triggered:
            job.status, job.reason = "cancelled", "trigger_no_longer_present"
            root.status = "cancelled"
            continue
        original = db.get(Reminder, root.id)
        reminder = Reminder(user_id=user_id, session_id=root.session_id, category=root.category,
                            priority=original.priority, message=original.message, reason_codes=["snooze_due"],
                            expires_at=at + timedelta(minutes=10), created_at=at)
        db.add(reminder)
        db.flush()
        retry = Intervention(
            id=reminder.id, user_id=user_id, session_id=root.session_id, namespace="live", category=root.category,
            strategy=root.strategy, status="awaiting_display", source=root.source, created_at=at,
            context={"preferences": preferences.model_dump(by_alias=True, mode="json"), "verification": {}},
            decision={**root.decision, "rootId": root.decision.get("rootId", root.id), "parentId": root.id, "reminderId": reminder.id},
        )
        db.add(retry)
        if expression:
            retry.context = {**retry.context, "expressionBaseline": expression["baseline"], "expressionHypothesis": expression["label"]}
        root.status = "rescheduled"
        job.status, job.dispatched_id, job.reason = "dispatched", reminder.id, "ready_for_display"
        delivered.append(reminder.id)
        db.flush()
    return delivered


def run_tick() -> None:
    with SessionLocal() as db:
        users = set(db.scalars(select(ScheduledIntervention.user_id).where(ScheduledIntervention.namespace == "live", ScheduledIntervention.status == "pending")))
        users.update(db.scalars(select(Intervention.user_id).where(Intervention.namespace == "live", Intervention.status.in_(ACTIVE))))
    for user_id in users:
        with SessionLocal() as db:
            lock_user(db, user_id)
            dispatch(db, user_id, utcnow())
            db.commit()


async def scheduler_loop(stop: asyncio.Event) -> None:
    while not stop.is_set():
        try:
            await asyncio.to_thread(run_tick)
        except Exception:
            logger.exception("Reminder scheduler tick failed; persisted jobs will retry")
        try:
            await asyncio.wait_for(stop.wait(), timeout=2)
        except TimeoutError:
            pass
