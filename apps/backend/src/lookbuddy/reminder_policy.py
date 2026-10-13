from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from .models import Intervention, Reminder, ReminderResponse, ScheduledIntervention, StateSnapshot, WorkSession, utcnow
from .display_budget import next_display_at
from .learning import choose
from .emotion_service import candidate as emotion_candidate
from .preferences import preferences_for


@dataclass(frozen=True)
class Candidate:
    category: str
    priority: int
    message: str
    reason_codes: list[str]


class ReminderPolicy:
    """Rules gate candidates; a finite-context bandit chooses strategy and cadence."""

    global_cooldown = timedelta(minutes=15)
    ignored_type_cooldown = timedelta(minutes=15)

    def decide(self, db: Session, snapshot: StateSnapshot) -> Reminder | None:
        preferences = preferences_for(db, snapshot.user_id)
        if preferences.busy:
            return None
        if snapshot.presence != "online":
            return None
        if db.scalar(select(Intervention.id).where(
            Intervention.user_id == snapshot.user_id,
            Intervention.namespace == "live",
            Intervention.status.in_(("awaiting_display", "observing", "action_started", "verifying", "monitoring", "silent_observing")),
        ).limit(1)):
            return None

        now = utcnow()
        if next_display_at(db, snapshot.user_id, now) > now:
            return None
        if db.scalar(select(ScheduledIntervention.id).where(
            ScheduledIntervention.user_id == snapshot.user_id, ScheduledIntervention.namespace == "live",
            ScheduledIntervention.status == "pending",
        ).limit(1)):
            return None

        candidates = self._candidates(snapshot, now, preferences.sedentary_minutes)
        session = db.get(WorkSession, snapshot.session_id)
        if session and now - session.started_at.replace(tzinfo=timezone.utc) < timedelta(minutes=90):
            candidates = [item for item in candidates if "no_drink_observed" not in item.reason_codes]
        expression = emotion_candidate(db, snapshot.user_id, snapshot.session_id, "live", now) if preferences.companion_enabled else None
        if expression:
            candidates.append(Candidate("emotion", 52, "刚刚观察到一些表情线索，要不要暂停一下，照顾一下此刻的状态？",
                                        ["visible_expression_stable_30s", "emotion_hypothesis_not_confirmed", expression["label"]]))
        if not preferences.posture_enabled:
            candidates = [item for item in candidates if item.category not in {"sedentary", "posture"}]
        if not candidates:
            return None

        for candidate in sorted(candidates, key=lambda item: item.priority, reverse=True):
            if self._is_suppressed(db, snapshot.user_id, candidate.category, now):
                continue
            recent = db.scalar(select(func.count(Intervention.id)).where(
                Intervention.user_id == snapshot.user_id, Intervention.namespace == "live",
                Intervention.displayed_at > now - timedelta(hours=1),
            )) or 0
            selection = choose(db, snapshot.user_id, "live", candidate.category, now, recent,
                               visual_state=expression["label"] if candidate.category == "emotion" else None)
            message = {
                "posture_light": "试着坐直，放松一下肩膀。",
                "short_stretch": "要不要做一次短伸展，再调整到自然坐姿？",
                "short_break": "要不要起来休息一下？我会继续记录起身与休息过程。",
                "emotion_inquiry": "此刻想聊两句，还是安静待一会儿？你可以随时关闭这条交互。",
                "gentle_companion": "我在这里陪着你。可以松松肩膀，给自己留一点余地。",
                "breathing": "要不要一起缓慢吸气，再慢慢呼气？按舒服的节奏来，可以随时停下。",
            }.get(selection["strategy"], candidate.message)
            reminder = Reminder(
                user_id=snapshot.user_id,
                session_id=snapshot.session_id,
                category=candidate.category,
                priority=candidate.priority,
                message=message,
                reason_codes=candidate.reason_codes,
                expires_at=now + timedelta(minutes=10),
                status="silent" if selection["strategy"] == "silent" else "pending",
            )
            reminder._selection = selection
            if candidate.category == "emotion":
                reminder._expression_baseline = expression["baseline"]
                reminder._expression_label = expression["label"]
            return reminder
        return None

    def _candidates(self, snapshot: StateSnapshot, now: datetime, sedentary_minutes: int = 60) -> list[Candidate]:
        result: list[Candidate] = []
        if snapshot.sedentary_ms >= sedentary_minutes * 60 * 1000:
            over = snapshot.sedentary_ms - sedentary_minutes * 60 * 1000
            result.append(
                Candidate(
                    "sedentary",
                    min(100, 65 + int(over / 60000)),
                    "已经连续工作一段时间了，要不要起来活动一下？",
                    [f"continuous_presence_{sedentary_minutes}m", "no_recent_break"],
                )
            )
        if snapshot.bad_posture_streak_ms >= 60_000 or snapshot.bad_posture_ratio_5m >= 0.30:
            result.append(
                Candidate(
                    "posture",
                    min(100, 58 + int(snapshot.bad_posture_ratio_5m * 40)),
                    "最近坐姿有些紧绷，试着坐直并放松一下肩膀？",
                    ["bad_posture_streak" if snapshot.bad_posture_streak_ms >= 60_000 else "bad_posture_ratio_5m"],
                )
            )
        if snapshot.last_drink_at is None:
            result.append(Candidate("drink", 45, "工作间隙记得补充一点水。", ["no_drink_observed"]))
        else:
            last_drink = snapshot.last_drink_at
            if last_drink.tzinfo is None:
                last_drink = last_drink.replace(tzinfo=timezone.utc)
            gap = now - last_drink
            if gap >= timedelta(minutes=90):
                result.append(
                    Candidate(
                        "drink",
                        min(100, 55 + int((gap - timedelta(minutes=90)).total_seconds() / 120)),
                        "距离上次喝水有一会儿了，记得补水。",
                        ["no_drink_90m"],
                    )
                )
        if snapshot.fatigue_signals_10m >= 2:
            result.append(
                Candidate(
                    "fatigue",
                    min(100, 75 + snapshot.fatigue_signals_10m * 5),
                    "刚刚出现了一些疲劳线索，建议离开屏幕休息两分钟。",
                    ["fatigue_signals_repeated"],
                )
            )
        return result

    def _is_suppressed(self, db: Session, user_id: str, category: str, now: datetime) -> bool:
        ignored = db.scalar(
            select(func.count(ReminderResponse.id))
            .join(Reminder, Reminder.id == ReminderResponse.reminder_id)
            .where(
                Reminder.user_id == user_id,
                Reminder.category == category,
                ReminderResponse.action == "ignored",
                ReminderResponse.created_at >= now - timedelta(days=7),
            )
        ) or 0
        if ignored < 2:
            return False
        latest_ignored = db.scalar(
            select(ReminderResponse)
            .join(Reminder, Reminder.id == ReminderResponse.reminder_id)
            .where(
                Reminder.user_id == user_id,
                Reminder.category == category,
                ReminderResponse.action == "ignored",
            )
            .order_by(desc(ReminderResponse.created_at))
            .limit(1)
        )
        if latest_ignored is None:
            return False
        ignored_at = latest_ignored.created_at
        if ignored_at.tzinfo is None:
            ignored_at = ignored_at.replace(tzinfo=timezone.utc)
        return now - ignored_at < self.ignored_type_cooldown

