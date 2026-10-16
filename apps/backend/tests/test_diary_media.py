from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from lookbuddy.database import SessionLocal
from lookbuddy.models import DomainEvent, EventMediaLink, MediaAsset, Reminder
from lookbuddy.migrations import migrate
from lookbuddy.database import engine


def device(client, auth):
    response = client.post('/v1/devices', headers=auth, json={
        'name': 'Desk', 'provider': 'eufy', 'providerDeviceRef': 'camera-sn', 'platform': 'android'})
    assert response.status_code == 201, response.text
    return response.json()['id']


def batch(device_id, **overrides):
    return {'events': [{'deviceId': device_id, 'providerEventId': 'sdk-event-1',
        'occurredAt': datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        'mediaItems': [{'providerMediaKey': 'thumbnail', 'kind': 'image', 'role': 'thumbnail'},
                       {'providerMediaKey': 'video', 'kind': 'video', 'role': 'clip'}], **overrides}]}


def test_roundtrip_idempotency_links_and_account_cleanup(client, auth):
    body = batch(device(client, auth))
    first = client.post('/v1/diary-device-events/batch', headers=auth, json=body)
    assert first.status_code == 200, first.text
    assert client.post('/v1/diary-device-events/batch', headers=auth, json=body).json() == first.json()
    row = first.json()['events'][0]
    day = datetime.fromisoformat(body['events'][0]['occurredAt']).astimezone(ZoneInfo('Asia/Shanghai')).date()
    entries = client.get(f'/v1/diary-page/{day}', headers=auth).json()['entries']
    assert len(entries) == 1
    assert {m['kind'] for m in entries[0]['mediaItems']} == {'image', 'video'}
    assert entries[0]['mediaItems'][0]['providerDeviceRef'] == 'camera-sn'
    path = f"/v1/events/{row['eventId']}/media/{row['mediaIds'][0]}"
    for _ in range(2):
        assert client.delete(path, headers=auth).status_code == 200
    client.post('/v1/diary-device-events/batch', headers=auth, json=body)
    assert len(client.get(f'/v1/diary-page/{day}', headers=auth).json()['entries'][0]['mediaItems']) == 1
    assert client.put(path, headers=auth).status_code == 200
    mark = {'eventId': 'mark_media_test', 'occurredAt': body['events'][0]['occurredAt'], 'note': '记住'}
    assert client.post('/v1/marks', headers=auth, json=mark).status_code == 200
    assert client.put(f"/v1/events/mark_media_test/media/{row['mediaIds'][0]}", headers=auth).status_code == 200
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(MediaAsset)) == 2
        assert db.scalar(select(func.count()).select_from(Reminder)) == 0
    assert client.delete('/v1/account', headers=auth).status_code == 204
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(MediaAsset)) == 0
        assert db.scalar(select(func.count()).select_from(EventMediaLink)) == 0


def test_atomic_ownership_and_strict_metadata(client, auth):
    own_device = device(client, auth)
    other = client.post('/v1/auth/register', json={'email': 'other@example.com', 'password': 'correct-horse', 'displayName': 'Other'}).json()
    other_auth = {'Authorization': 'Bearer ' + other['accessToken']}
    body = batch(own_device)
    assert client.post('/v1/diary-device-events/batch', headers=other_auth, json=body).status_code == 404
    invalid = {'events': body['events'] + [{**body['events'][0], 'deviceId': 'missing'}]}
    assert client.post('/v1/diary-device-events/batch', headers=auth, json=invalid).status_code == 404
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(DomainEvent)) == 0
    with_image = batch(own_device, imageBase64='not-accepted')
    assert client.post('/v1/diary-device-events/batch', headers=auth, json=with_image).status_code == 422
    row = client.post('/v1/diary-device-events/batch', headers=auth, json=body).json()['events'][0]
    assert client.put(f"/v1/events/{row['eventId']}/media/{row['mediaIds'][0]}", headers=other_auth).status_code == 404
    changed = {'events': [{**body['events'][0], 'occurredAt': '2020-01-01T00:00:00Z'}]}
    assert client.post('/v1/diary-device-events/batch', headers=auth, json=changed).status_code == 409


def test_migration_adds_media_to_existing_database(client, auth):
    own_device = device(client, auth)
    EventMediaLink.__table__.drop(engine)
    MediaAsset.__table__.drop(engine)
    migrate(engine)
    migrate(engine)
    assert client.post('/v1/diary-device-events/batch', headers=auth, json=batch(own_device)).status_code == 200
