from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from random import Random

import pytest
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from lookbuddy.database import engine
from lookbuddy.display_budget import next_display_at, require_display
from lookbuddy.learning import choose, posterior, settle, settle_response
from lookbuddy.models import Intervention, PolicyState, Reminder, RewardLedger, ScheduledIntervention, StateSnapshot
from lookbuddy.scheduler import dispatch
from test_actions import frame


def ids(client, auth):
    user = client.get('/v1/me', headers=auth).json()['id']
    session = client.post('/v1/sessions', headers=auth, json={}).json()['id']
    return user, session


def live(db, user, session, at, displayed=None, category='posture', interval=15):
    reminder = Reminder(user_id=user, session_id=session, category=category, priority=60,
                        message='test', reason_codes=[], created_at=at, expires_at=at + timedelta(minutes=10))
    db.add(reminder)
    db.flush()
    decision = choose(db, user, 'live', category, at, rng=Random(7))
    decision['intervalMinutes'] = interval
    value = Intervention(id=reminder.id, user_id=user, session_id=session, category=category,
                         namespace='live', strategy=decision['strategy'], source='web_vision',
                         status='observing' if displayed else 'awaiting_display',
                         created_at=at, displayed_at=displayed, due_at=at + timedelta(hours=24),
                         context={'preferences': {'breakMinutes': 10}, 'verification': {}}, decision=decision)
    db.add(value)
    db.flush()
    return value


def test_display_budget_uses_real_deliveries_all_categories_and_boundary(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        undelivered = live(db, user, session, now)
        assert next_display_at(db, user, now) == now  # pending delivery is not a count
        undelivered.status = 'unknown'
        for offset, category in ((0, 'posture'), (15, 'drink'), (30, 'fatigue')):
            value = live(db, user, session, now + timedelta(minutes=offset), category=category)
            require_display(db, value, now + timedelta(minutes=offset))
            value.displayed_at = now + timedelta(minutes=offset)
            value.status = 'unknown'
            db.flush()
        fourth = live(db, user, session, now + timedelta(minutes=45))
        with pytest.raises(HTTPException) as error:
            require_display(db, fourth, now + timedelta(minutes=45))
        assert error.value.status_code == 409
        assert next_display_at(db, user, now + timedelta(minutes=45)) == now + timedelta(hours=1)
        require_display(db, fourth, now + timedelta(hours=1))
        db.commit()


def test_learned_interval_can_exceed_hard_minimum(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        value = live(db, user, session, now, displayed=now, interval=120)
        value.status = 'unknown'
        db.flush()
        assert next_display_at(db, user, now + timedelta(minutes=61)) == now + timedelta(minutes=120)


def test_concurrent_display_acknowledgements_do_not_break_budget(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        first, second = live(db, user, session, now), live(db, user, session, now)
        keys = [first.id, second.id]
        db.commit()
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda key: client.post(f'/v1/interventions/{key}/displayed', headers=auth,
                                                         json={"claimId": "web:same-client"}), keys))
    assert sorted(response.status_code for response in responses) == [200, 409]
    accepted = keys[next(index for index, response in enumerate(responses) if response.status_code == 200)]
    assert client.post(f'/v1/interventions/{accepted}/displayed', headers=auth, json={"claimId": "web:same-client"}).status_code == 200
    with Session(engine) as db:
        assert len(list(db.scalars(select(Intervention).where(Intervention.displayed_at.is_not(None))))) == 1


def test_verified_rewards_are_incremental_idempotent_and_demo_isolated(client, auth):
    user, session = ids(client, auth)
    response = client.post('/v1/debug/interventions', headers=auth, json={'sessionId': session, 'category': 'posture', 'learning': True, 'seed': 7})
    value = response.json()
    start = datetime.fromisoformat(value['displayedAt'])
    for second in range(0, 142, 2):
        frame(client, auth, value['id'], start, second)
    assert frame(client, auth, value['id'], start, 140)['accepted'] is False
    rewards = client.get('/v1/learning/rewards?namespace=demo', headers=auth).json()
    assert {reward['key'] for reward in rewards} == {'completed', 'sustained', 'display_cost'}
    assert sum(reward['amount'] for reward in rewards) == pytest.approx(0.95)
    assert client.get('/v1/learning/rewards?namespace=live', headers=auth).json() == []
    assert client.get('/v1/learning/states?namespace=live', headers=auth).json() == []
    states = client.get('/v1/learning/states?namespace=demo', headers=auth).json()
    expected_alpha = 1 + 0.25 * 0.5 ** (120 / (7 * 86400)) + 0.75
    assert all(row['parameters']['alpha'] == pytest.approx(expected_alpha) for row in states)


def test_unknown_without_action_does_not_train_or_create_negative_rewards(client, auth):
    user, session = ids(client, auth)
    value = client.post('/v1/debug/interventions', headers=auth, json={'sessionId': session, 'category': 'posture', 'learning': True}).json()
    start = datetime.fromisoformat(value['displayedAt'])
    frame(client, auth, value['id'], start, 0, posture='unknown', quality='unavailable')
    client.post(f'/v1/sessions/{session}/finish', headers=auth)
    assert client.get('/v1/learning/rewards?namespace=demo', headers=auth).json() == []
    assert client.get('/v1/learning/states?namespace=demo', headers=auth).json() == []


def test_snooze_only_updates_timing_and_demo_response_is_idempotent(client, auth):
    user, session = ids(client, auth)
    value = client.post('/v1/debug/interventions', headers=auth, json={'sessionId': session, 'category': 'posture', 'learning': True}).json()
    for _ in range(2):
        assert client.post(f"/v1/debug/interventions/{value['id']}/respond", headers=auth, json={'action': 'snoozed'}).status_code == 200
    states = client.get('/v1/learning/states?namespace=demo', headers=auth).json()
    assert all(':interval:' in row['arm'] for row in states)
    assert all(row['parameters']['beta'] == pytest.approx(1.1) for row in states)
    assert len(client.get('/v1/learning/rewards?namespace=demo', headers=auth).json()) == 1


def test_negative_history_changes_selection_and_decays(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        cold = choose(db, user, 'demo', 'posture', now, rng=Random(7))
        assert cold['strategy'] == 'posture_light'
        for _ in range(12):
            value = Intervention(user_id=user, session_id=session, namespace='demo', category='posture',
                                 strategy='posture_light', source='simulated', status='ignored', context={}, decision=cold)
            db.add(value)
            db.flush()
            settle_response(db, value, 'ignored', now)
        decision = choose(db, user, 'demo', 'posture', now, rng=Random(7))
        assert decision['strategySelection']['estimatedProbabilities']['posture_light'] < 0.15
        assert decision['intervalSelection']['estimatedProbabilities']['15'] < 0.15
        row = db.scalar(select(PolicyState).where(PolicyState.context_key == 'posture:pooled', PolicyState.arm == 'posture:strategy:posture_light'))
        assert posterior(row, now + timedelta(days=7))['beta'] - 1 == pytest.approx((row.parameters['beta'] - 1) / 2)


def test_persisted_snooze_revalidates_budget_then_dispatches_once(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        root = live(db, user, session, now, displayed=now)
        root.status = 'snoozed'
        db.add(ScheduledIntervention(user_id=user, intervention_id=root.id, namespace='live', due_at=now + timedelta(minutes=5)))
        key = root.id
        db.commit()
    # New session simulates restoring persisted jobs after restart.
    with Session(engine) as db:
        assert dispatch(db, user, now + timedelta(minutes=5)) == []
        job = db.scalar(select(ScheduledIntervention))
        assert job.reason == 'busy_or_budget'
        assert job.due_at.replace(tzinfo=timezone.utc) == now + timedelta(minutes=15)
        db.commit()
    with Session(engine) as db:
        db.add(StateSnapshot(user_id=user, session_id=session, captured_at=now + timedelta(minutes=15), presence='online', bad_posture_streak_ms=60_000))
        db.flush()
        delivered = dispatch(db, user, now + timedelta(minutes=15))
        assert len(delivered) == 1
        assert dispatch(db, user, now + timedelta(minutes=15)) == []
        child = db.get(Intervention, delivered[0])
        assert child.decision['rootId'] == key
        assert db.get(Intervention, key).status == 'rescheduled'
        db.commit()


def test_snooze_cancels_after_natural_action_completion(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        root = live(db, user, session, now, displayed=now)
        root.context = {'verification': {'completedAt': now.isoformat()}}
        db.add(ScheduledIntervention(user_id=user, intervention_id=root.id, namespace='live', due_at=now))
        db.flush()
        assert dispatch(db, user, now) == []
        assert db.scalar(select(ScheduledIntervention)).status == 'cancelled'


def test_live_debug_response_cannot_mutate_and_busy_blocks_delivery(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        value = live(db, user, session, now)
        key = value.id
        db.commit()
    assert client.post(f'/v1/debug/interventions/{key}/respond', headers=auth, json={'action': 'ignored'}).status_code == 422
    client.patch('/v1/preferences', headers=auth, json={'busy': True})
    assert client.post(f'/v1/interventions/{key}/displayed', headers=auth).status_code == 409


def test_reset_only_removes_current_user_demo_records(client, auth):
    user, session = ids(client, auth)
    value = client.post('/v1/debug/interventions', headers=auth, json={'sessionId': session, 'category': 'posture', 'learning': True}).json()
    client.post(f"/v1/debug/interventions/{value['id']}/respond", headers=auth, json={'action': 'ignored'})
    with Session(engine) as db:
        db.add(PolicyState(user_id=user, namespace='live', context_key='retained', arm='retained', parameters={'alpha': 2}))
        db.commit()
    assert client.post('/v1/debug/learning/reset', headers=auth).status_code == 200
    assert client.get('/v1/learning/states?namespace=demo', headers=auth).json() == []
    assert client.get('/v1/learning/rewards?namespace=demo', headers=auth).json() == []
    assert client.get('/v1/interventions?namespace=demo', headers=auth).json() == []
    assert client.get('/v1/learning/states?namespace=live', headers=auth).json()[0]['arm'] == 'retained'


def test_retry_children_attribute_rewards_to_original_chain_once(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        root = live(db, user, session, now)
        first = live(db, user, session, now)
        second = live(db, user, session, now)
        for child in (first, second):
            child.decision = {**root.decision, 'rootId': root.id}
            settle(db, child, 'completed', 0.25, now)
            settle(db, child, 'sustained', 0.75, now)
        rewards = list(db.scalars(select(RewardLedger)))
        assert len(rewards) == 2
        assert all(row.intervention_id == root.id for row in rewards)
        assert sum(row.amount for row in rewards) == 1


def test_scheduler_defers_while_action_is_being_verified(client, auth):
    user, session = ids(client, auth)
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        root = live(db, user, session, now - timedelta(minutes=20), displayed=now - timedelta(minutes=20))
        root.status = 'verifying'
        db.add(ScheduledIntervention(user_id=user, intervention_id=root.id, namespace='live', due_at=now))
        db.flush()
        assert dispatch(db, user, now) == []
        assert db.scalar(select(ScheduledIntervention)).reason == 'action_in_progress'
