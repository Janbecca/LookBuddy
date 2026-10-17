from __future__ import annotations

import json
from collections import Counter
from datetime import date, datetime, time, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Diary, DomainEvent, User, utcnow
from .llm_service import complete, model_config


class DiaryCurator:
    """Selects diary-worthy facts without asking an LLM to make privacy decisions."""

    def collect(self, db: Session, user: User, diary_date: date) -> list[dict[str, Any]]:
        zone = ZoneInfo(user.timezone)
        start_local = datetime.combine(diary_date, time.min, tzinfo=zone)
        end_local = start_local + timedelta(days=1)
        events = db.scalars(
            select(DomainEvent)
            .where(
                DomainEvent.user_id == user.id,
                DomainEvent.occurred_at >= start_local.astimezone(timezone.utc),
                DomainEvent.occurred_at < end_local.astimezone(timezone.utc),
            )
            .order_by(DomainEvent.occurred_at)
        ).all()

        events = [event for event in events if event.source.get("namespace") != "demo" and event.source.get("kind") not in {"simulated", "replay"}]
        counts = Counter(event.type for event in events)
        candidates: list[dict[str, Any]] = []
        drinks = counts["behavior.drink_detected"]
        if drinks:
            candidates.append(self._item("hydration", f"记录到 {drinks} 次饮水", {"count": drinks}, 65, ["repeated_health_action"]))

        completed_breaks = counts["behavior.break_completed"] + counts["behavior.stretch_completed"]
        if completed_breaks:
            candidates.append(self._item("break", f"完成 {completed_breaks} 次主动休息", {"count": completed_breaks}, 80, ["completed_positive_action"]))

        memories = [event for event in events if event.type == "memory.marked"]
        for event in memories[:5]:
            note = str(event.payload.get("note") or "主动标记了一个工作瞬间")[:200]
            candidates.append(self._item("memory", note, {"eventId": event.id, "note": note}, 95, ["user_explicitly_marked"]))

        sessions = [event for event in events if event.type == "work.session_completed"]
        for event in sessions[:5]:
            duration_ms = event.duration_ms or int(event.payload.get("durationMs") or 0)
            if duration_ms >= 20 * 60 * 1000:
                minutes = round(duration_ms / 60000)
                candidates.append(self._item("focus_block", f"完成一段约 {minutes} 分钟的连续工作", {"durationMs": duration_ms}, min(90, 60 + minutes // 2), ["meaningful_duration"]))

        fatigue = counts["behavior.yawn_detected"] + counts["behavior.eyes_closed"]
        if fatigue >= 3:
            candidates.append(self._item("fatigue", "有一段时间出现了较集中的疲劳线索", {"signalCount": fatigue}, 45, ["aggregated_not_single_signal"]))

        return sorted(candidates, key=lambda item: item["score"], reverse=True)[:12]

    @staticmethod
    def _item(kind: str, title: str, facts: dict[str, Any], score: int, reasons: list[str]) -> dict[str, Any]:
        return {"kind": kind, "title": title, "facts": facts, "score": score, "reasonCodes": reasons}


class DiaryWriter:
    async def write(self, diary_date: date, candidates: list[dict[str, Any]]) -> tuple[str, str]:
        if not candidates:
            return "今天还没有足够明确的工位记录。等有值得记住的片段时，我会帮你整理。", "template"

        config = model_config("diary")
        if config is None:
            return self._template(diary_date, candidates), "template"

        prompt = (
            "你是 LookBuddy 工位日记写作者。只能依据给定结构化事实写一篇简短中文日记；"
            "不评价效率、不诊断健康、不补充未观察到的内容。语气温和自然，120到220字。\n"
            f"日期：{diary_date.isoformat()}\n事实：{json.dumps(candidates, ensure_ascii=False)}"
        )
        try:
            content = await complete(config, [{"role": "user", "content": prompt}])
            return content, "router" if config.mode == "legacy" else "llm"
        except (httpx.HTTPError, KeyError, TypeError, ValueError):
            pass
        return self._template(diary_date, candidates), "template_fallback"

    @staticmethod
    def _template(diary_date: date, candidates: list[dict[str, Any]]) -> str:
        lines = [f"{diary_date.isoformat()}，今天有这些值得记住的片段："]
        lines.extend(f"• {candidate['title']}。" for candidate in candidates)
        lines.append("这些记录只描述可见行为，不评价你今天做得好不好。")
        return "\n".join(lines)


async def generate_diary(db: Session, user: User, diary_date: date) -> Diary:
    candidates = DiaryCurator().collect(db, user, diary_date)
    content, generated_by = await DiaryWriter().write(diary_date, candidates)
    diary = db.scalar(select(Diary).where(Diary.user_id == user.id, Diary.diary_date == diary_date))
    if diary is None:
        diary = Diary(user_id=user.id, diary_date=diary_date, candidates=candidates, content=content, generated_by=generated_by)
        db.add(diary)
    else:
        if diary.status == "edited":
            return diary
        diary.candidates = candidates
        diary.content = content
        diary.generated_by = generated_by
        diary.status = "generated"
        diary.updated_at = utcnow()
    db.commit()
    db.refresh(diary)
    return diary
