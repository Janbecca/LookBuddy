"""Owner-scoped device media metadata; bytes are read through the client SDK."""
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import select
from sqlalchemy.orm import Session

from .database import get_db
from .display_budget import lock_user, utc
from .models import Device, DomainEvent, EventMediaLink, MediaAsset, User, utcnow
from .security import get_current_user


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MediaIn(StrictModel):
    providerMediaKey: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,128}$")
    kind: Literal["image", "video"]
    role: Literal["thumbnail", "picture", "clip"]
    durationMs: int | None = Field(default=None, ge=0, le=86400000)

    @model_validator(mode="after")
    def valid_role(self):
        if (self.kind == "video") != (self.role == "clip"):
            raise ValueError("Video media must use clip role")
        return self


class DeviceEventIn(StrictModel):
    deviceId: str = Field(min_length=1, max_length=40)
    providerEventId: str = Field(min_length=1, max_length=255)
    occurredAt: datetime
    mediaItems: list[MediaIn] = Field(default_factory=list, max_length=32)

    @field_validator("occurredAt")
    @classmethod
    def valid_time(cls, value):
        if value.tzinfo is None or value > utcnow() + timedelta(seconds=30):
            raise ValueError("occurredAt must be timezone aware and not in the future")
        return value.astimezone(timezone.utc)


class DeviceEventBatch(StrictModel):
    events: list[DeviceEventIn] = Field(max_length=100)


router = APIRouter(prefix="/v1")


def owned_device(db, user, device_id):
    device = db.get(Device, device_id)
    if device is None or device.user_id != user.id or device.provider != "eufy" or not device.provider_device_ref:
        raise HTTPException(404, "Bound eufy device not found")
    return device


def media_public(asset, device):
    return {"id": asset.id, "deviceId": device.id, "provider": asset.provider,
            "providerDeviceRef": device.provider_device_ref, "providerEventId": asset.provider_event_id,
            "providerMediaKey": asset.provider_media_key, "kind": asset.kind, "role": asset.role,
            "capturedAt": utc(asset.captured_at), "durationMs": asset.duration_ms,
            "accessMode": asset.access_mode, "availability": asset.availability,
            "availabilityCheckedAt": utc(asset.availability_checked_at) if asset.availability_checked_at else None,
            "expiresAt": utc(asset.expires_at) if asset.expires_at else None}


def media_for_events(db, user, event_ids):
    result = {}
    if not event_ids:
        return result
    rows = db.execute(select(EventMediaLink, MediaAsset, Device)
        .join(MediaAsset, EventMediaLink.media_id == MediaAsset.id)
        .join(Device, MediaAsset.device_id == Device.id)
        .where(EventMediaLink.event_id.in_(event_ids), EventMediaLink.active.is_(True),
               MediaAsset.user_id == user.id, Device.user_id == user.id)
        .order_by(EventMediaLink.sort_order, MediaAsset.captured_at, MediaAsset.id))
    for link, asset, device in rows:
        result.setdefault(link.event_id, []).append(media_public(asset, device))
    return result


@router.post("/diary-device-events/batch")
def sync_events(body: DeviceEventBatch, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    lock_user(db, user.id)
    # Validate the entire batch before inserting anything.
    for item in body.events:
        owned_device(db, user, item.deviceId)
    result = []
    for item in body.events:
        digest = sha256(f"{user.id}\0{item.deviceId}\0{item.providerEventId}".encode()).hexdigest()[:48]
        event_id = f"de_{digest}"
        event = db.get(DomainEvent, event_id)
        if event is None:
            event = DomainEvent(id=event_id, user_id=user.id, device_id=item.deviceId,
                type="device.security.event", occurred_at=item.occurredAt,
                source={"kind": "device_event", "provider": "eufy", "namespace": "live"},
                payload={"providerEventId": item.providerEventId},
                evidence={"method": "eufy_app_sdk"}, privacy={"containsImage": False, "retention": "event_only"})
            db.add(event)
            db.flush()
        elif event.user_id != user.id or event.device_id != item.deviceId or utc(event.occurred_at) != item.occurredAt:
            raise HTTPException(409, "Device event identity conflict")
        ids = []
        for index, media in enumerate(item.mediaItems):
            asset = db.scalar(select(MediaAsset).where(MediaAsset.user_id == user.id,
                MediaAsset.device_id == item.deviceId, MediaAsset.provider_event_id == item.providerEventId,
                MediaAsset.provider_media_key == media.providerMediaKey))
            if asset is None:
                asset = MediaAsset(user_id=user.id, device_id=item.deviceId, provider_event_id=item.providerEventId,
                    provider_media_key=media.providerMediaKey, kind=media.kind, role=media.role,
                    captured_at=item.occurredAt, duration_ms=media.durationMs)
                db.add(asset)
                db.flush()
            elif asset.kind != media.kind or asset.role != media.role:
                raise HTTPException(409, "Media identity conflict")
            link = db.scalar(select(EventMediaLink).where(EventMediaLink.event_id == event.id, EventMediaLink.media_id == asset.id))
            if link is None:
                db.add(EventMediaLink(event_id=event.id, media_id=asset.id, sort_order=index))
                db.flush()
            ids.append(asset.id)
        result.append({"eventId": event.id, "mediaIds": ids})
    db.commit()
    return {"events": result}


@router.put("/events/{event_id}/media/{media_id}")
def attach_media(event_id: str, media_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return set_link(db, user, event_id, media_id, True)


@router.delete("/events/{event_id}/media/{media_id}")
def detach_media(event_id: str, media_id: str, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    return set_link(db, user, event_id, media_id, False)


def set_link(db, user, event_id, media_id, active):
    lock_user(db, user.id)
    event, asset = db.get(DomainEvent, event_id), db.get(MediaAsset, media_id)
    if event is None or event.user_id != user.id or asset is None or asset.user_id != user.id:
        raise HTTPException(404, "Event or media not found")
    owned_device(db, user, asset.device_id)
    if event.source.get("namespace") == "demo" or event.source.get("kind") in {"simulated", "replay"}:
        raise HTTPException(422, "Cannot associate device media with simulated records")
    link = db.scalar(select(EventMediaLink).where(EventMediaLink.event_id == event_id, EventMediaLink.media_id == media_id))
    if link is None:
        db.add(EventMediaLink(event_id=event_id, media_id=media_id, active=active))
    else:
        link.active = active
    db.commit()
    return {"eventId": event_id, "mediaId": media_id, "linked": active}
