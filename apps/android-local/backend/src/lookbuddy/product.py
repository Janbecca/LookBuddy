"""Product-page APIs. Demo identity is device scoped and never verifies a phone."""
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import json
import re
import secrets
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import JSON, Date, DateTime, ForeignKey, String, UniqueConstraint, select, text
from sqlalchemy.orm import Mapped, Session, mapped_column

from .api import issue_tokens
from .config import get_settings
from .database import Base, get_db
from .display_budget import lock_user, utc
from .greeting_prompt import SYSTEM_PROMPT
from .buddy_prompt import chat_entry, greeting_fallback, valid_proactive_message
from .models import Diary, DomainEvent, StateSnapshot, User, WorkSession, utcnow
from .schemas import TokenPair
from .security import get_current_user, hash_password


class DemoIdentity(Base):
    __tablename__ = "product_demo_identities"
    identity_key: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), unique=True)
    phone_hint: Mapped[str] = mapped_column(String(20))


class ProductProfile(Base):
    __tablename__ = "product_profiles"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    work_start: Mapped[str] = mapped_column(String(5), default="09:00")
    work_end: Mapped[str] = mapped_column(String(5), default="18:00")


class DailyMood(Base):
    __tablename__ = "daily_moods"
    __table_args__ = (UniqueConstraint("user_id", "mood_date", name="uq_daily_mood"),)
    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    mood_date: Mapped[date] = mapped_column(Date)
    mood: Mapped[str] = mapped_column(String(16))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ProductLease(Base):
    __tablename__ = "product_session_leases"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    session_id: Mapped[str] = mapped_column(ForeignKey("work_sessions.id", ondelete="CASCADE"))
    owner: Mapped[str] = mapped_column(String(64))
    heartbeat_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class GreetingCache(Base):
    __tablename__ = "product_greeting_cache"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    scene: Mapped[str] = mapped_column(String(32))
    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    result: Mapped[dict] = mapped_column(JSON)


class DemoPhoneIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    phone: str = Field(default="", description="Demo label only; arbitrary text or empty is accepted")
    code: str = Field(default="", repr=False, description="Ignored in demo login; never verified or stored")
    installationId: str = Field(min_length=32, max_length=64, pattern=r"^[a-zA-Z0-9-]+$")
    displayName: str = Field(default="体验用户", min_length=1, max_length=80)


class ProfilePatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    displayName: str | None = Field(default=None, min_length=1, max_length=80)
    timezone: str | None = None

    @field_validator("timezone")
    @classmethod
    def valid_zone(cls, value):
        if value is not None:
            try:
                ZoneInfo(value)
            except (ZoneInfoNotFoundError, ValueError):
                raise ValueError("Unknown timezone")
        return value


class ScheduleIn(BaseModel):
    start: str = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")
    end: str = Field(pattern=r"^(?:[01]\d|2[0-3]):[0-5]\d$")


class OwnerIn(BaseModel):
    installationId: str = Field(min_length=32, max_length=64)


class MoodIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mood: Literal["Happy", "Calm", "Tired", "Sad"]


class MarkIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    eventId: str = Field(min_length=8, max_length=64, pattern=r"^[a-zA-Z0-9_-]+$")
    occurredAt: datetime
    note: str = Field(default="主动记录了一个瞬间", max_length=200)


class GreetingIn(BaseModel):
    scene: Literal["morning_seated", "morning_unknown", "evening_rain", "evening"] = "morning_unknown"
    recent_message: str = Field(default="", max_length=500)


router = APIRouter(prefix="/v1")
compatibility = APIRouter()


@router.post("/auth/demo-phone", response_model=TokenPair)
def demo_phone(body: DemoPhoneIn, db: Session = Depends(get_db)):
    settings = get_settings()
    if not settings.demo_login_enabled or settings.env not in {"development", "competition"}:
        raise HTTPException(403, "Demo phone login is disabled; it never verifies a phone")
    # Same phone on a different install MUST NOT expose another install's data.
    phone = body.phone.strip().removeprefix("+86")
    identity = hashlib.sha256(f"{body.installationId}:{phone}".encode()).hexdigest()
    if db.get_bind().dialect.name == "sqlite":
        db.execute(text("BEGIN IMMEDIATE"))
    row = db.get(DemoIdentity, identity)
    if row is None:
        user = User(email=f"demo-{secrets.token_hex(16)}@example.com",
                    password_hash=hash_password(secrets.token_urlsafe(32)),
                    display_name=body.displayName, timezone="Asia/Shanghai")
        db.add(user)
        db.flush()
        hint = phone[:3] + "****" + phone[-4:] if re.fullmatch(r"1[3-9]\d{9}", phone) else "演示账号"
        db.add(DemoIdentity(identity_key=identity, user_id=user.id, phone_hint=hint))
    else:
        user = db.get(User, row.user_id)
    return issue_tokens(db, user)


def profile_json(db, user):
    identity = db.scalar(select(DemoIdentity).where(DemoIdentity.user_id == user.id))
    return {"id": user.id, "displayName": user.display_name, "timezone": user.timezone,
            "demo": identity is not None, "phoneMasked": identity.phone_hint if identity else None,
            "email": None if identity else user.email}


@router.get("/profile")
def profile(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return profile_json(db, user)


@router.patch("/me")
def patch_me(body: ProfilePatch, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if body.displayName is not None:
        user.display_name = body.displayName.strip() or "体验用户"
    if body.timezone is not None:
        user.timezone = body.timezone
    db.commit()
    return profile_json(db, user)


@router.get("/work-schedule")
def schedule(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(ProductProfile, user.id)
    return {"start": row.work_start if row else "09:00", "end": row.work_end if row else "18:00", "timezone": user.timezone}


@router.patch("/work-schedule")
def patch_schedule(body: ScheduleIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if body.start == body.end:
        raise HTTPException(422, "Start and end cannot be equal")
    row = db.get(ProductProfile, user.id)
    if row is None:
        row = ProductProfile(user_id=user.id)
        db.add(row)
    row.work_start, row.work_end = body.start, body.end
    db.commit()
    return schedule(user, db)


def current_session(db, user):
    lease = db.get(ProductLease, user.id)
    if lease is None:
        return None
    value = db.get(WorkSession, lease.session_id)
    if value.status != "active":
        return None
    if utcnow() > utc(lease.heartbeat_at) + timedelta(seconds=90):
        # A lost app cannot accumulate unobserved hours indefinitely.
        value.status, value.ended_at = "finished", utc(lease.heartbeat_at)
        return None
    return {"id": value.id, "startedAt": utc(value.started_at), "status": value.status, "owner": lease.owner}


@router.get("/sessions/current")
def current(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    result = current_session(db, user)
    db.commit()
    return result


@router.post("/work/start")
def start_work(body: OwnerIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    lock_user(db, user.id)
    result = current_session(db, user)
    if result:
        db.commit()
        return result
    value = WorkSession(user_id=user.id)
    db.add(value)
    db.flush()
    lease = db.get(ProductLease, user.id)
    if lease is None:
        lease = ProductLease(user_id=user.id)
        db.add(lease)
    lease.session_id, lease.owner, lease.heartbeat_at = value.id, body.installationId, utcnow()
    db.commit()
    return current_session(db, user)


@router.post("/work/heartbeat")
def heartbeat(body: OwnerIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    lock_user(db, user.id)
    result = current_session(db, user)
    if result and result["owner"] == body.installationId:
        db.get(ProductLease, user.id).heartbeat_at = utcnow()
    db.commit()
    return result


@router.get("/moods")
def moods(start: date, end: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if end < start or (end - start).days > 366:
        raise HTTPException(422, "Invalid date range")
    return [{"date": row.mood_date, "mood": row.mood, "source": "self_report", "updatedAt": row.updated_at}
            for row in db.scalars(select(DailyMood).where(DailyMood.user_id == user.id, DailyMood.mood_date >= start,
                                                        DailyMood.mood_date <= end).order_by(DailyMood.mood_date))]


@router.put("/moods/{day}")
def put_mood(day: date, body: MoodIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if day > datetime.now(ZoneInfo(user.timezone)).date():
        raise HTTPException(422, "Cannot self-report a future date")
    lock_user(db, user.id)
    key = f"{user.id}:{day}"
    row = db.get(DailyMood, key)
    if row is None:
        row = DailyMood(id=key, user_id=user.id, mood_date=day)
        db.add(row)
    row.mood, row.updated_at = body.mood, utcnow()
    db.commit()
    return {"date": day, "mood": row.mood, "source": "self_report"}


@router.get("/moods/{day}")
def get_mood(day: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(DailyMood, f"{user.id}:{day}")
    return {"date": day, "mood": row.mood, "source": "self_report"} if row else None


@router.delete("/moods/{day}", status_code=204)
def delete_mood(day: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    row = db.get(DailyMood, f"{user.id}:{day}")
    if row:
        db.delete(row)
        db.commit()


def bounds(day, user):
    start = datetime.combine(day, time.min, tzinfo=ZoneInfo(user.timezone))
    return start.astimezone(timezone.utc), (start + timedelta(days=1)).astimezone(timezone.utc)


def events_for(db, user, day):
    begin, end = bounds(day, user)
    return list(db.scalars(select(DomainEvent).where(DomainEvent.user_id == user.id,
        DomainEvent.occurred_at >= begin, DomainEvent.occurred_at < end).order_by(DomainEvent.occurred_at)))


def merged_ms(intervals):
    total, previous_end = 0, None
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if previous_end is None or start > previous_end:
            total += (end - start).total_seconds() * 1000
        elif end > previous_end:
            total += (end - previous_end).total_seconds() * 1000
        previous_end = max(previous_end, end) if previous_end else end
    return round(total)


def work_ms(db, user, begin, end):
    intervals = []
    now = utcnow()
    for row in db.scalars(select(WorkSession).where(WorkSession.user_id == user.id, WorkSession.started_at < end)):
        finish = utc(row.ended_at) if row.ended_at else now
        lease = db.scalar(select(ProductLease).where(ProductLease.session_id == row.id))
        if lease and row.ended_at is None:
            finish = min(finish, utc(lease.heartbeat_at) + timedelta(seconds=30))
        elif row.ended_at is None:
            last = db.scalar(select(StateSnapshot).where(StateSnapshot.session_id == row.id).order_by(StateSnapshot.captured_at.desc()).limit(1))
            finish = min(finish, utc(last.captured_at) + timedelta(seconds=30)) if last else utc(row.started_at)
        if finish > begin:
            intervals.append((max(utc(row.started_at), begin), min(finish, end)))
    return merged_ms(intervals)


def latest_state(db, user):
    value = db.scalar(select(StateSnapshot).where(StateSnapshot.user_id == user.id).order_by(StateSnapshot.captured_at.desc()).limit(1))
    if value is None:
        return {"fresh": False, "posture": None, "presence": "unknown", "capturedAt": None}
    fresh = utcnow() - utc(value.captured_at) <= timedelta(seconds=30)
    return {"fresh": fresh, "posture": ("bad" if value.bad_posture_streak_ms > 0 else "unknown") if fresh else None,
            "presence": value.presence if fresh else "unknown", "capturedAt": utc(value.captured_at)}


@router.get("/state/latest")
def state_latest(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return latest_state(db, user)


@router.get("/home/summary")
def summary(day: date | None = None, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    day = day or datetime.now(ZoneInfo(user.timezone)).date()
    current_session(db, user)
    db.commit()
    begin, end = bounds(day, user)
    rows = [r for r in events_for(db, user, day) if live_event(r)]
    return {"date": day, "timezone": user.timezone,
            "workMs": work_ms(db, user, begin, end),
            "monthWorkMs": work_ms(db, user, bounds(day.replace(day=1), user)[0], end),
            "drinkCount": sum(r.type == "behavior.drink_detected" for r in rows),
            "stretchCount": sum(r.type == "behavior.stretch_completed" for r in rows),
            "breakMs": sum(r.duration_ms or 0 for r in rows if r.type == "behavior.break_completed"),
            "companionDays": max(1, (day - utc(user.created_at).astimezone(ZoneInfo(user.timezone)).date()).days + 1),
            "state": latest_state(db, user), "scope": "live", "workMetric": "session_duration_not_focus",
            "dataQuality": {"work": "observed_session_intervals", "behaviors": "recorded_events_only",
                            "posture": "unknown_without_fresh_evidence", "companionDays": "days_since_account_created"}}


@router.post("/marks")
def mark(body: MarkIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    if body.occurredAt.tzinfo is None or body.occurredAt > utcnow() + timedelta(seconds=30):
        raise HTTPException(422, "Mark time must be timezone aware and not in the future")
    lock_user(db, user.id)
    previous = db.get(DomainEvent, body.eventId)
    if previous:
        if previous.user_id != user.id or previous.type != "memory.marked" or previous.payload.get("note") != body.note or utc(previous.occurred_at) != utc(body.occurredAt):
            raise HTTPException(409, "Event ID conflict")
        return {"eventId": previous.id, "saved": True, "duplicate": True, "media": "not_recorded"}
    active = current_session(db, user)
    session_id = active["id"] if active and body.occurredAt >= active["startedAt"] else None
    if session_id is None:
        matched = db.scalar(select(WorkSession).where(WorkSession.user_id == user.id,
            WorkSession.started_at <= body.occurredAt, WorkSession.ended_at >= body.occurredAt)
            .order_by(WorkSession.started_at.desc()).limit(1))
        session_id = matched.id if matched else None
    db.add(DomainEvent(id=body.eventId, user_id=user.id, session_id=session_id,
        type="memory.marked", occurred_at=body.occurredAt, source={"kind": "explicit_user_mark", "namespace": "live"},
        payload={"note": body.note, "media": "not_recorded"}, evidence={"method": "user_click"}))
    db.commit()
    return {"eventId": body.eventId, "saved": True, "duplicate": False, "media": "not_recorded"}


@router.get("/diary-page/{day}")
def diary_page(day: date, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    value = db.scalar(select(Diary).where(Diary.user_id == user.id, Diary.diary_date == day))
    labels = {"memory.marked": "主动记录", "behavior.drink_detected": "饮水", "behavior.stretch_completed": "伸展",
              "behavior.break_completed": "休息", "work.session_completed": "陪伴"}
    return {"date": day, "content": value.content if value else "", "status": value.status if value else "empty",
            "entries": [{"id": r.id, "time": utc(r.occurred_at).astimezone(ZoneInfo(user.timezone)).strftime("%H:%M"),
                "title": r.payload.get("note") or labels.get(r.type, "工位记录"), "description": r.payload.get("description", ""),
                "category": labels.get(r.type, "记录"), "media": "not_recorded"} for r in events_for(db, user, day)
                if live_event(r)]}


def live_event(row):
    return row.source.get("namespace") != "demo" and row.source.get("kind") not in {"simulated", "replay"}


@compatibility.post("/buddy/greeting")
@router.post("/buddy/greeting")
async def greeting(body: GreetingIn, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    settings = get_settings()
    if body.scene == "evening_rain":
        return {"show": False, "message": "", "source": "weather_unavailable", "weather_source": "unavailable", "chatEntry": None}
    cached = db.get(GreetingCache, user.id)
    if cached and utcnow() - utc(cached.generated_at) < timedelta(minutes=1):
        if cached.scene != body.scene or cached.result.get("message") == body.recent_message:
            return {"show": False, "message": "", "source": "cooldown", "weather_source": "unavailable", "chatEntry": None}
        if not cached.result.get("show") or valid_proactive_message(cached.result.get("message")):
            return {**cached.result, "chatEntry": chat_entry(body.scene) if cached.result.get("show") else None}
        # Old cache entries are returned as complete reviewed copy, never truncated.
        message = greeting_fallback(body.scene)
        show = message != body.recent_message
        return {"show": show, "message": message if show else "", "source": "fallback",
                "weather_source": "unavailable", "chatEntry": chat_entry(body.scene) if show else None}
    fallback = greeting_fallback(body.scene)
    result = {"show": body.recent_message != fallback, "message": fallback, "source": "fallback", "weather_source": "unavailable", "chatEntry": None}
    if settings.greeting_base_url and settings.greeting_api_key:
        try:
            async with httpx.AsyncClient(timeout=12) as client:
                response = await client.post(settings.greeting_base_url.rstrip("/") + "/chat/completions",
                    headers={"Authorization": "Bearer " + settings.greeting_api_key},
                    json={"model": settings.greeting_model, "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": json.dumps({"scene": body.scene, "presence": "unknown",
                            "weather_valid": False, "local_time": datetime.now(ZoneInfo(user.timezone)).isoformat(),
                            "recent_message": body.recent_message}, ensure_ascii=False)}], "temperature": 0.5})
                response.raise_for_status()
                parsed = json.loads(response.json()["choices"][0]["message"]["content"])
                if isinstance(parsed, dict) and type(parsed.get("show")) is bool and isinstance(parsed.get("message"), str) and (not parsed["show"] or valid_proactive_message(parsed["message"])):
                    result = {**result, "show": parsed["show"], "message": parsed["message"].strip() if parsed["show"] else "", "source": "glm"}
        except (httpx.HTTPError, ValueError, KeyError, TypeError, IndexError):
            pass
    result["chatEntry"] = chat_entry(body.scene) if result["show"] else None
    if result["message"] == body.recent_message:
        result["show"], result["message"], result["chatEntry"] = False, "", None
    # Re-check under the per-user lock after the network await, avoiding duplicate
    # inserts when two screens request their first greeting concurrently.
    lock_user(db, user.id)
    cached = db.get(GreetingCache, user.id, populate_existing=True)
    if cached is not None and utcnow() - utc(cached.generated_at) < timedelta(minutes=1):
        return {"show": False, "message": "", "source": "cooldown", "weather_source": "unavailable", "chatEntry": None}
    if cached is None:
        cached = GreetingCache(user_id=user.id)
        db.add(cached)
    cached.scene, cached.generated_at, cached.result = body.scene, utcnow(), result
    db.commit()
    return result
