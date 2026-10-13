"""Finite-context discounted Thompson sampling with an auditable reward ledger."""
from datetime import datetime, timezone
from random import Random, SystemRandom
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Intervention, PolicyState, RewardLedger, User, utcnow

POLICY_VERSION = "contextual-ts-v1"
REWARD_VERSION = "behavior-v1"
INTERVALS = (15, 30, 60, 120)
STRATEGIES = {
    "posture": ("posture_light", "short_stretch", "short_break", "silent"),
    "sedentary": ("short_break", "short_stretch", "silent"),
    "emotion": ("emotion_inquiry", "gentle_companion", "breathing", "silent"),
}


def utc(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def context_key(db: Session, user_id: str, category: str, at: datetime, burden: int) -> str:
    user = db.get(User, user_id)
    try:
        local = utc(at).astimezone(ZoneInfo(user.timezone))
    except (KeyError, ValueError):
        local = utc(at)
    period = "morning" if local.hour < 12 else "afternoon" if local.hour < 18 else "evening"
    return f"{category}:{period}:{'recent' if burden else 'quiet'}"


def posterior(row: PolicyState | None, at: datetime) -> dict:
    if row is None:
        return {"alpha": 1.0, "beta": 1.0, "updates": 0}
    p = row.parameters
    previous = datetime.fromisoformat(p.get("lastAt", utc(row.updated_at).isoformat()))
    days = max(0, (utc(at) - utc(previous)).total_seconds() / 86400)
    decay = 0.5 ** (days / 7)
    return {"alpha": 1 + (p.get("alpha", 1) - 1) * decay,
            "beta": 1 + (p.get("beta", 1) - 1) * decay, "updates": p.get("updates", 0)}


def choose(db: Session, user_id: str, namespace: str, category: str, at: datetime, burden: int = 0, rng=None, visual_state: str | None = None) -> dict:
    rng = rng or SystemRandom()
    key = context_key(db, user_id, category, at, burden)
    if category == "emotion" and visual_state:
        key += f":{visual_state}"
    strategies = STRATEGIES.get(category, ("plain",))
    rows = list(db.scalars(select(PolicyState).where(PolicyState.user_id == user_id, PolicyState.namespace == namespace)))

    def select_stage(stage: str, options: tuple, default):
        stats = {}
        fallback = []
        for option in options:
            arm = f"{category}:{stage}:{option}"
            exact = next((row for row in rows if row.context_key == key and row.arm == arm), None)
            pooled = next((row for row in rows if row.context_key == f"{category}:pooled" and row.arm == arm), None)
            # Until the local context has enough evidence, use pooled per-user history.
            row = exact if exact and exact.parameters.get("updates", 0) >= 5 else pooled
            fallback.append(row is not exact or exact is None)
            stats[str(option)] = posterior(row, at)
        cold = not any(p["updates"] for p in stats.values())
        counts = {str(option): 0 for option in options}
        selected = default
        if cold:
            counts[str(default)] = 256
        else:
            for trial in range(256):
                winner = max(options, key=lambda option: rng.betavariate(stats[str(option)]["alpha"], stats[str(option)]["beta"]))
                counts[str(winner)] += 1
                if trial == 0:
                    selected = winner
        return selected, {"coldStart": cold, "pooledFallback": any(fallback), "posterior": stats,
                          "estimatedProbability": counts[str(selected)] / 256,
                          "estimatedProbabilities": {option: count / 256 for option, count in counts.items()}, "draws": 256}

    strategy, strategy_info = select_stage("strategy", strategies, strategies[0])
    interval, interval_info = select_stage("interval", INTERVALS, 15)
    return {
        "policyVersion": POLICY_VERSION, "rewardVersion": "expression-v1" if category == "emotion" else REWARD_VERSION, "contextKey": key,
        "strategy": strategy, "intervalMinutes": interval,
        "visualStateHypothesis": visual_state,
        "strategySelection": strategy_info, "intervalSelection": interval_info,
        "estimatedSelectionProbability": strategy_info["estimatedProbability"] * interval_info["estimatedProbability"],
        "probabilityMethod": "256 posterior draws per stage; estimate, not exact propensity",
        "learningApplied": not (strategy_info["coldStart"] and interval_info["coldStart"]),
    }


def settle(db: Session, value: Intervention, key: str, amount: float, at: datetime, stages=("strategy", "interval")) -> None:
    if value.decision.get("policyVersion") != POLICY_VERSION:
        return  # Old P1 records do not silently train a different model.
    root = db.get(Intervention, value.decision.get("rootId", value.id)) or value
    if db.scalar(select(RewardLedger.id).where(RewardLedger.intervention_id == root.id, RewardLedger.settlement_key == key)):
        return
    db.add(RewardLedger(user_id=root.user_id, intervention_id=root.id, settlement_key=key,
                       version=root.decision.get("rewardVersion", REWARD_VERSION), amount=amount,
                       components={"stages": list(stages), "evidenceInterventionId": value.id, "eventAt": utc(at).isoformat()}))
    category = root.category
    for stage in stages:
        option = root.strategy if stage == "strategy" else root.decision.get("intervalMinutes", 15)
        arm = f"{category}:{stage}:{option}"
        for context in (root.decision["contextKey"], f"{category}:pooled"):
            row = db.scalar(select(PolicyState).where(
                PolicyState.user_id == root.user_id, PolicyState.namespace == root.namespace,
                PolicyState.context_key == context, PolicyState.arm == arm,
            ))
            p = posterior(row, at)
            if row is None:
                row = PolicyState(user_id=root.user_id, namespace=root.namespace, context_key=context, arm=arm)
                db.add(row)
            previous = row.parameters.get("lastAt") if row.parameters else None
            effective_at = max(utc(at), datetime.fromisoformat(previous)) if previous else utc(at)
            row.parameters = {"alpha": p["alpha"] + max(0, amount), "beta": p["beta"] + max(0, -amount),
                              "updates": p["updates"] + 1, "lastAt": effective_at.isoformat()}
    db.flush()  # Subsequent components within the same transaction see this settlement.


def settle_result(db: Session, value: Intervention, status: str, at: datetime) -> None:
    # Unknown and interrupted are not evidence of disliking a reminder.
    if status == "improved":
        settle(db, value, "sustained", 0.75, at)
    elif status == "recurred":
        settle(db, value, "recurrence", -0.25, at)
    if status in {"improved", "recurred"} and value.displayed_at is not None:
        settle(db, value, "display_cost", -0.05, at)


def settle_response(db: Session, value: Intervention, action: str, at: datetime) -> None:
    if action == "accepted":
        settle(db, value, "accepted", 0.05, at, ("strategy",))
    elif action == "ignored":
        settle(db, value, "ignored", -0.6, at)
    elif action == "snoozed":
        # Timing evidence only; does not punish the chosen companion/action style.
        settle(db, value, "snoozed", -0.1, at, ("interval",))
