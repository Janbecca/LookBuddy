"""Visible expression hypotheses and weak, non-causal reaction evidence."""
from datetime import datetime, timedelta

from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .action_service import ACTIVE, aware, millis_since, outcome, record, stamp
from .learning import settle
from .models import ExpressionObservation, Intervention, utcnow
from .preferences import preferences_for
from .schemas import ExpressionIn

FEATURES = ("smile", "browDown", "frown", "mouthPress", "lookDown", "eyeClosed")
TRIGGER_MS = 30_000
IMMEDIATE_MS = 30_000
FOLLOWUP_MS = 120_000
GAP_MS = 3000


def classify(features: dict, quality: str, busy: bool) -> tuple[str, float, list[str]]:
    if quality != "observable":
        return "unknown", 0, ["face_or_image_quality_insufficient"]
    smile, brow, frown, press = (features[key] for key in ("smile", "browDown", "frown", "mouthPress"))
    if features["eyeClosed"] >= 0.65:
        return "unknown", 0, ["closed_eyes_are_not_an_emotion_label"]
    if smile >= 0.45 and brow < 0.4:
        return "joyful", 0.65, ["sustained_smile_hypothesis"]
    if brow >= 0.55 and press >= 0.45 and smile < 0.15:
        return "possibly_angry", 0.5, ["brow_and_pressed_lips_ambiguous"]
    if frown >= 0.45 and features["lookDown"] >= 0.45 and smile < 0.15:
        return "possibly_low", 0.5, ["frown_and_downward_gaze_ambiguous"]
    if (brow >= 0.35 or press >= 0.5) and smile < 0.15:
        return "possibly_stressed", 0.5, ["facial_tension_ambiguous"]
    if busy:
        return "focused", 0.5, ["manual_busy_context_not_visual_proof_of_focus"]
    if smile >= 0.15 and brow < 0.2 and frown < 0.2:
        return "good", 0.55, ["mild_smile_hypothesis"]
    return "unknown", 0, ["neutral_expression_does_not_prove_focus_or_low_mood"]


def ingest(db: Session, user_id: str, body: ExpressionIn) -> tuple[ExpressionObservation, bool]:
    at = aware(body.captured_at)
    payload = body.model_dump(by_alias=True, mode="json")
    payload["capturedAt"] = stamp(at)
    existing = db.get(ExpressionObservation, body.expression_id)
    if existing:
        if existing.user_id != user_id or existing.input_payload != payload:
            raise HTTPException(409, "Expression ID reused with different evidence")
        return existing, False
    if body.namespace == "live" and (body.source != "webcam" or abs((utcnow() - at).total_seconds()) > 30):
        raise HTTPException(422, "Live expression input requires current webcam evidence; replay belongs in demo")
    previous = db.scalar(select(ExpressionObservation).where(
        ExpressionObservation.user_id == user_id, ExpressionObservation.session_id == body.session_id,
        ExpressionObservation.namespace == body.namespace,
    ).order_by(ExpressionObservation.captured_at.desc()).limit(1))
    if previous and at <= aware(previous.captured_at):
        raise HTTPException(422, "Expression times must increase")
    features = body.features.model_dump(by_alias=True)
    quality = body.quality
    if not body.camera_healthy:
        quality = "unavailable"
    elif not all(features[key] for key in ("faceVisible", "frontal", "lightingOk")):
        quality = "insufficient"
    label, confidence, reasons = classify(features, quality, preferences_for(db, user_id).busy)
    duration = 0
    if previous and label != "unknown" and previous.suggested_label == label and previous.quality == "observable":
        elapsed = (at - aware(previous.captured_at)).total_seconds() * 1000
        if elapsed <= GAP_MS:
            duration = previous.stable_duration_ms + round(elapsed)
    value = ExpressionObservation(
        id=body.expression_id, user_id=user_id, session_id=body.session_id, namespace=body.namespace, source=body.source,
        captured_at=at, quality=quality, suggested_label=label, confidence=confidence,
        stable_duration_ms=duration, reason_codes=reasons, features=features, input_payload=payload,
    )
    db.add(value)
    db.flush()
    return value, True


def candidate(db: Session, user_id: str, session_id: str, namespace: str, at: datetime) -> dict | None:
    latest = db.scalar(select(ExpressionObservation).where(
        ExpressionObservation.user_id == user_id, ExpressionObservation.session_id == session_id,
        ExpressionObservation.namespace == namespace,
    ).order_by(ExpressionObservation.captured_at.desc()).limit(1))
    if latest is None or latest.quality != "observable" or latest.suggested_label == "unknown" or latest.confidence < 0.45 or latest.stable_duration_ms < TRIGGER_MS:
        return None
    if not timedelta(0) <= aware(at) - aware(latest.captured_at) <= timedelta(seconds=5):
        return None
    rows = list(db.scalars(select(ExpressionObservation).where(
        ExpressionObservation.user_id == user_id, ExpressionObservation.session_id == session_id,
        ExpressionObservation.namespace == namespace,
        ExpressionObservation.captured_at >= aware(latest.captured_at) - timedelta(seconds=30),
    ).order_by(ExpressionObservation.captured_at)))
    sums = dict.fromkeys(FEATURES, 0.0)
    observed = 0
    for left, right in zip(rows, rows[1:]):
        duration = (aware(right.captured_at) - aware(left.captured_at)).total_seconds() * 1000
        if left.quality == right.quality == "observable" and 0 < duration <= GAP_MS:
            observed += duration
            for key in FEATURES:
                sums[key] += left.features[key] * duration
    if observed < 24_000:
        return None
    return {"label": latest.suggested_label, "confidence": latest.confidence, "expressionId": latest.id,
            "baseline": {"features": {key: value / observed for key, value in sums.items()}, "observedMs": observed,
                         "capturedAt": stamp(latest.captured_at), "interpretation": "visible_features_not_confirmed_emotion"}}


def observe(db: Session, value: Intervention, expression: ExpressionObservation) -> None:
    at = aware(expression.captured_at)
    anchor = value.displayed_at or (datetime.fromisoformat(value.context["observationStartedAt"]) if value.context.get("observationStartedAt") else None)
    if anchor is None or at < aware(anchor):
        return
    state = dict(value.context.get("verification", {}))
    tracker = dict(state.get("expression", {}))
    previous_at = tracker.get("lastAt")
    record(db, value, f"expression:{expression.id}", "expression_sample", at, expression.quality,
           {"expressionId": expression.id, "features": expression.features, "exposure": "display_ack_only_not_proof_of_reading"})
    valid = expression.quality == "observable"
    gap = previous_at is not None and millis_since(at, previous_at) > GAP_MS
    elapsed = (at - aware(anchor)).total_seconds() * 1000
    baseline = value.context.get("expressionBaseline")
    if not valid or gap or not baseline or (previous_at is None and elapsed > GAP_MS):
        outcome(db, value, state, "unknown", at, "expression_window_not_observable")
    elif value.due_at and at >= aware(value.due_at):
        outcome(db, value, state, "unknown", at, "expression_observation_deadline")
    else:
        if previous_at is not None:
            previous_elapsed = (datetime.fromisoformat(previous_at) - aware(anchor)).total_seconds() * 1000
            for phase, start, end in (("immediate", 0, IMMEDIATE_MS), ("followup", IMMEDIATE_MS, IMMEDIATE_MS + FOLLOWUP_MS)):
                duration = max(0, min(elapsed, end) - max(previous_elapsed, start))
                totals = dict(tracker.get(phase, {"observedMs": 0, **dict.fromkeys(FEATURES, 0.0)}))
                totals["observedMs"] += duration
                for key in FEATURES:
                    totals[key] += tracker["lastFeatures"][key] * duration
                tracker[phase] = totals
        tracker["lastAt"], tracker["lastFeatures"] = stamp(at), expression.features
        if elapsed >= IMMEDIATE_MS:
            value.status = "monitoring"
        if elapsed >= IMMEDIATE_MS + FOLLOWUP_MS:
            immediate = tracker.get("immediate", {})
            followup = tracker.get("followup", {})
            if immediate.get("observedMs", 0) < 24_000 or followup.get("observedMs", 0) < 114_000:
                outcome(db, value, state, "unknown", at, "expression_coverage_insufficient")
            else:
                after = {key: followup[key] / followup["observedMs"] for key in FEATURES}
                before = baseline["features"]
                negative = lambda features: max(features["browDown"], features["frown"], features["mouthPress"])
                smile_change = after["smile"] - before["smile"]
                tension_change = negative(after) - negative(before)
                state["expressionComparison"] = {"before": before, "after": after, "smileChange": smile_change,
                                                 "tensionChange": tension_change, "causalEffectProven": False}
                if tension_change <= -0.15 or (smile_change >= 0.15 and tension_change <= 0.05):
                    outcome(db, value, state, "expression_relaxed", at, "visible_expression_shift_only")
                    settle(db, value, "expression_shift", 0.1, at)
                    if value.displayed_at:
                        settle(db, value, "expression_display_cost", -0.01, at)
                elif tension_change >= 0.15:
                    repeats = (db.scalar(select(func.count(Intervention.id)).where(
                        Intervention.user_id == value.user_id, Intervention.namespace == value.namespace,
                        Intervention.category == "emotion", Intervention.strategy == value.strategy,
                        Intervention.id != value.id, Intervention.status == "expression_tense",
                        Intervention.created_at >= at - timedelta(days=7),
                    )) or 0) + 1
                    state["possibleBurdenEpisodes"] = repeats
                    outcome(db, value, state, "expression_tense", at, "visible_tension_increase_not_proof_of_annoyance")
                    if repeats >= 3 and value.displayed_at:
                        settle(db, value, "expression_possible_burden", -0.05, at)
                else:
                    outcome(db, value, state, "expression_unchanged", at, "no_visible_change_not_failure")
    state["expression"] = tracker
    value.context = {**value.context, "verification": state}
