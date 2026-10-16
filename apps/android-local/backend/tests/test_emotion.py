from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.orm import Session

from lookbuddy.database import engine
from lookbuddy.emotion_service import classify
from lookbuddy.models import EmotionCorrection, ExpressionObservation, LocalCapture
from lookbuddy.learning import choose

BASE = {"faceVisible": True, "frontal": True, "lightingOk": True, "smile": 0.05,
        "browDown": 0.4, "frown": 0.1, "mouthPress": 0.4, "lookDown": 0.1, "eyeClosed": 0.05}
RELAXED = {**BASE, "smile": 0.55, "browDown": 0.1, "mouthPress": 0.1, "frown": 0.05}
TENSE = {**BASE, "browDown": 0.9, "mouthPress": 0.8}


def session(client, auth):
    return client.post('/v1/sessions', headers=auth, json={}).json()['id']


def expression(client, auth, session_id, at, features=BASE, namespace='demo', quality='observable', expression_id=None):
    payload = {'expressionId': expression_id or f'exp_{uuid4().hex}', 'sessionId': session_id,
               'namespace': namespace, 'source': 'simulated' if namespace == 'demo' else 'webcam',
               'capturedAt': at.isoformat(), 'quality': quality, 'features': features}
    response = client.post('/v1/expressions', headers=auth, json=payload)
    assert response.status_code == 200, response.text
    return response.json()


def baseline(client, auth, session_id, namespace='demo', clock=None):
    start = clock or datetime.now(timezone.utc)
    for second in range(31):
        result = expression(client, auth, session_id, start + timedelta(seconds=second), namespace=namespace)
    assert result['observation']['stableDurationMs'] == 30_000
    return result['observation']


def episode(client, auth, features=RELAXED, gap=False):
    session_id = session(client, auth)
    baseline(client, auth, session_id)
    response = client.post('/v1/debug/interventions', headers=auth, json={'sessionId': session_id, 'category': 'emotion', 'learning': True, 'seed': 7})
    assert response.status_code == 201, response.text
    value = response.json()
    anchor = datetime.fromisoformat(value['displayedAt'] or value['context']['observationStartedAt']) + timedelta(seconds=1)
    for second in range(0, 32 if gap else 152, 2):
        result = expression(client, auth, session_id, anchor + timedelta(seconds=second), features)
    if gap:
        result = expression(client, auth, session_id, anchor + timedelta(seconds=36), features)
    return session_id, value['id'], result['intervention']


@pytest.mark.parametrize('features,busy,label', [
    ({**BASE, 'smile': 0.6, 'browDown': 0.1}, False, 'joyful'),
    ({**BASE, 'smile': 0.2, 'browDown': 0.1, 'mouthPress': 0.1}, False, 'good'),
    ({**BASE, 'browDown': 0.1, 'mouthPress': 0.1}, True, 'focused'),
    (BASE, False, 'possibly_stressed'),
    ({**BASE, 'browDown': 0.6, 'mouthPress': 0.5}, False, 'possibly_angry'),
    ({**BASE, 'browDown': 0.1, 'mouthPress': 0.1, 'frown': 0.5, 'lookDown': 0.5}, False, 'possibly_low'),
    ({**BASE, 'browDown': 0.1, 'mouthPress': 0.1}, False, 'unknown'),
    ({**BASE, 'eyeClosed': 0.8}, False, 'unknown'),
])
def test_seven_hypotheses_and_neutral_fallback(features, busy, label):
    actual, confidence, reasons = classify(features, 'observable', busy)
    assert actual == label
    assert confidence <= 0.65
    assert reasons


def test_quality_overrides_label_and_input_is_idempotent(client, auth):
    session_id = session(client, auth)
    at = datetime.now(timezone.utc)
    key = f'exp_{uuid4().hex}'
    bad = {**RELAXED, 'frontal': False}
    result = expression(client, auth, session_id, at, bad, expression_id=key)
    assert result['observation']['quality'] == 'insufficient'
    assert result['observation']['suggestedLabel'] == 'unknown'
    assert expression(client, auth, session_id, at, bad, expression_id=key)['accepted'] is False
    payload = {'expressionId': key, 'sessionId': session_id, 'namespace': 'demo', 'source': 'simulated',
               'capturedAt': at.isoformat(), 'quality': 'observable', 'features': RELAXED}
    assert client.post('/v1/expressions', headers=auth, json=payload).status_code == 409
    payload['namespace'] = 'live'
    payload['expressionId'] = f'exp_{uuid4().hex}'
    assert client.post('/v1/expressions', headers=auth, json=payload).status_code == 422


def test_companion_requires_30s_baseline(client, auth):
    session_id = session(client, auth)
    at = datetime.now(timezone.utc)
    for second in range(0, 29, 2):
        expression(client, auth, session_id, at + timedelta(seconds=second))
    assert client.post('/v1/debug/interventions', headers=auth, json={'sessionId': session_id, 'category': 'emotion'}).status_code == 422


def test_visible_relaxation_only_receives_small_reward_and_no_action_completion(client, auth):
    session_id, key, value = episode(client, auth)
    assert value['status'] == 'expression_relaxed'
    assert value['context']['verification']['expressionComparison']['causalEffectProven'] is False
    rewards = client.get('/v1/learning/rewards?namespace=demo', headers=auth).json()
    assert {row['key'] for row in rewards} == {'expression_shift', 'expression_display_cost'}
    assert sum(row['amount'] for row in rewards) == pytest.approx(0.09)
    assert all(row['version'] == 'expression-v1' for row in rewards)
    observations = client.get(f'/v1/interventions/{key}/observations', headers=auth).json()
    assert not any(row['kind'] == 'action_completed' for row in observations)
    assert client.get('/v1/learning/states?namespace=live', headers=auth).json() == []


def test_unchanged_and_missing_frames_do_not_generate_negative_rewards(client, auth):
    _, _, unchanged = episode(client, auth, features=BASE)
    assert unchanged['status'] == 'expression_unchanged'
    _, _, missing = episode(client, auth, gap=True)
    assert missing['status'] == 'unknown'
    assert client.get('/v1/learning/rewards?namespace=demo', headers=auth).json() == []


def test_tension_needs_three_consistent_episodes_before_weak_negative_update(client, auth):
    for index in range(3):
        _, _, value = episode(client, auth, features=TENSE)
        assert value['status'] == 'expression_tense'
        assert value['context']['verification']['possibleBurdenEpisodes'] == index + 1
        rewards = client.get('/v1/learning/rewards?namespace=demo', headers=auth).json()
        assert len(rewards) == (1 if index == 2 else 0)
    assert rewards[0]['amount'] == -0.05


def test_local_capture_correction_is_audited_without_images_or_reward(client, auth):
    session_id = session(client, auth)
    now = datetime.now(timezone.utc)
    result = expression(client, auth, session_id, now)
    original = result['observation']
    capture_id = f'cap_{uuid4().hex}'
    metadata = {'captureId': capture_id, 'expressionId': original['id'], 'capturedAt': now.isoformat()}
    rejected = client.post('/v1/emotions/captures', headers=auth, json={**metadata, 'image': 'data:image/jpeg;not-uploaded'})
    assert rejected.status_code == 422
    capture = client.post('/v1/emotions/captures', headers=auth, json=metadata)
    assert capture.status_code == 201
    assert capture.json()['imageStored'] is False
    correction = {'correctionId': f'cor_{uuid4().hex}', 'captureId': capture_id,
                  'suggestedLabel': original['suggestedLabel'], 'confirmedLabel': 'focused'}
    for _ in range(2):
        response = client.post('/v1/emotions/corrections', headers=auth, json=correction)
        assert response.status_code == 200
        assert response.json()['usedAsSatisfactionReward'] is False
    assert len(client.get('/v1/emotions/corrections?namespace=demo', headers=auth).json()) == 1
    assert client.get('/v1/expressions?namespace=demo', headers=auth).json()[0]['suggestedLabel'] == original['suggestedLabel']
    assert client.get('/v1/learning/rewards?namespace=demo', headers=auth).json() == []
    assert client.post('/v1/emotions/corrections', headers=auth, json={**correction, 'suggestedLabel': 'joyful'}).status_code == 409
    assert client.post('/v1/debug/learning/reset', headers=auth).status_code == 200
    assert client.get('/v1/expressions?namespace=demo', headers=auth).json() == []
    assert client.get('/v1/emotions/corrections?namespace=demo', headers=auth).json() == []


def test_live_companion_uses_global_rules_and_requires_enabled_setting(client, auth, monkeypatch):
    session_id = session(client, auth)
    now = datetime.now(timezone.utc)
    # Advance the verifier's clock, not production time, to test a real namespace window.
    for second in range(31):
        at = now - timedelta(seconds=30 - second)
        monkeypatch.setattr('lookbuddy.emotion_service.utcnow', lambda instant=at: instant)
        expression(client, auth, session_id, at, namespace='live')
    client.patch('/v1/preferences', headers=auth, json={'companionEnabled': False})
    payload = {'sessionId': session_id, 'capturedAt': datetime.now(timezone.utc).isoformat(), 'presence': 'online'}
    assert client.post('/v1/state-snapshots', headers=auth, json=payload).json()['reminder'] is None
    client.patch('/v1/preferences', headers=auth, json={'companionEnabled': True})
    response = client.post('/v1/state-snapshots', headers=auth, json=payload)
    assert response.status_code == 200, response.text
    value = response.json()['intervention']
    assert value['category'] == 'emotion'
    assert value['strategy'] == 'emotion_inquiry'
    assert value['context']['expressionBaseline']['observedMs'] >= 24_000
    client.patch('/v1/preferences', headers=auth, json={'busy': True})
    assert client.post(f"/v1/interventions/{value['id']}/displayed", headers=auth).status_code == 409


def test_capture_ownership_and_account_deletion_cover_expression_data(client, auth):
    session_id = session(client, auth)
    now = datetime.now(timezone.utc)
    value = expression(client, auth, session_id, now)['observation']
    capture_id = f'cap_{uuid4().hex}'
    metadata = {'captureId': capture_id, 'expressionId': value['id'], 'capturedAt': now.isoformat()}
    tokens = client.post('/v1/auth/register', json={'email': 'other@example.com', 'password': 'correct-horse', 'displayName': 'Other'}).json()
    other = {'Authorization': f"Bearer {tokens['accessToken']}"}
    assert client.post('/v1/emotions/captures', headers=other, json=metadata).status_code == 404
    client.post('/v1/emotions/captures', headers=auth, json=metadata)
    client.post('/v1/emotions/corrections', headers=auth, json={'correctionId': f'cor_{uuid4().hex}', 'captureId': capture_id,
                                                           'suggestedLabel': value['suggestedLabel'], 'confirmedLabel': 'good'})
    user_id = client.get('/v1/me', headers=auth).json()['id']
    assert client.delete('/v1/account', headers=auth).status_code == 204
    with Session(engine) as db:
        for model in (ExpressionObservation, LocalCapture, EmotionCorrection):
            assert db.scalar(select(model).where(model.user_id == user_id)) is None


def test_emotion_context_distinguishes_visible_state_hypotheses(client, auth):
    user_id = client.get('/v1/me', headers=auth).json()['id']
    now = datetime.now(timezone.utc)
    with Session(engine) as db:
        stressed = choose(db, user_id, 'demo', 'emotion', now, visual_state='possibly_stressed')
        joyful = choose(db, user_id, 'demo', 'emotion', now, visual_state='joyful')
        assert stressed['contextKey'] != joyful['contextKey']
        assert stressed['visualStateHypothesis'] == 'possibly_stressed'
