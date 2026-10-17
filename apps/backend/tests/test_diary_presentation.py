from types import SimpleNamespace
import pytest
from lookbuddy.diary_presentation import diary_event_details


@pytest.mark.parametrize('event_type,payload,category,label', [
    ('posture.bad_ended', {}, 'posture', '坐姿'),
    ('behavior.drink_detected', {}, 'drink', '饮水'),
    ('behavior.yawn_detected', {}, 'fatigue', '打哈欠'),
    ('intervention.improved', {'category': 'sedentary'}, 'sedentary', '久坐'),
    ('intervention.recurred', {'category': 'posture'}, 'posture', '坐姿'),
    ('intervention.unknown', {'category': 'drink'}, 'drink', '饮水'),
    ('device.security.event', {'providerType': 'posture'}, None, '设备事件'),
    ('device.security.event', {'providerType': '2'}, None, '设备事件'),
    ('custom.capture', {'category': 'drink', 'imageDataUrl': 'private-image'}, 'drink', '饮水'),
    ('custom.capture', {'category': {'bad': 'shape'}}, None, '记录'),
])
def test_classification_uses_explicit_semantics_not_sdk_codes(event_type, payload, category, label):
    result = diary_event_details(SimpleNamespace(type=event_type, payload=payload))
    assert result['triggerCategory'] == category
    assert result['triggerLabel'] == label
    assert result['eventType'] == event_type
    assert 'imageDataUrl' not in result


def test_existing_note_description_and_intervention_outcome_are_preserved():
    event = SimpleNamespace(type='intervention.improved', payload={'category': 'posture'})
    assert diary_event_details(event)['title'] == '坐姿提醒'
    assert diary_event_details(event)['description'] == '提醒后出现改善线索'
    event.payload.update(note='我记录的片段', description='设备同学的说明')
    assert diary_event_details(event)['title'] == '我记录的片段'
    assert diary_event_details(event)['description'] == '设备同学的说明'


def test_diary_api_exposes_trigger_metadata_and_keeps_reminder_outcomes_distinct(client, auth):
    events = [
        {'eventId': 'trigger-posture', 'type': 'posture.bad_ended', 'payload': {}},
        {'eventId': 'trigger-sedentary', 'type': 'intervention.improved', 'payload': {'category': 'sedentary'}},
        {'eventId': 'trigger-drink', 'type': 'behavior.drink_detected', 'payload': {}},
    ]
    for event in events:
        event.update(occurredAt='2026-10-17T03:00:00+00:00', source={'kind': 'device'})
    assert client.post('/v1/events/batch', headers=auth, json={'events': events}).status_code == 200
    entries = client.get('/v1/diary-page/2026-10-17', headers=auth).json()['entries']
    rows = {entry['id']: entry for entry in entries}
    assert rows['trigger-posture']['category'] == '坐姿'
    assert rows['trigger-sedentary']['triggerCategory'] == 'sedentary'
    assert rows['trigger-sedentary']['title'] == '久坐提醒'
    assert rows['trigger-drink']['category'] == '饮水'
    assert rows['trigger-drink']['eventType'] == 'behavior.drink_detected'
