from datetime import date, datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator, computed_field


from .buddy_prompt import chat_entry


class ApiModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, from_attributes=True)


class PreferencesPatch(ApiModel):
    model_config = ConfigDict(populate_by_name=True, from_attributes=True, extra="forbid")
    sedentary_minutes: Literal[30, 60, 90] | None = Field(default=None, alias="sedentaryMinutes")
    break_minutes: Literal[5, 10, 15, 20] | None = Field(default=None, alias="breakMinutes")
    busy: bool | None = None
    posture_enabled: bool | None = Field(default=None, alias="postureEnabled")
    companion_enabled: bool | None = Field(default=None, alias="companionEnabled")

    @field_validator("sedentary_minutes", "break_minutes", "busy", "posture_enabled", "companion_enabled")
    @classmethod
    def reject_explicit_null(cls, value):
        if value is None:
            raise ValueError("Use an omitted field to keep the existing value; null is not supported")
        return value


class PreferencesPublic(ApiModel):
    sedentary_minutes: Literal[30, 60, 90] = Field(default=60, serialization_alias="sedentaryMinutes")
    break_minutes: Literal[5, 10, 15, 20] = Field(default=10, serialization_alias="breakMinutes")
    busy: bool = False
    posture_enabled: bool = Field(default=True, serialization_alias="postureEnabled")
    companion_enabled: bool = Field(default=True, serialization_alias="companionEnabled")
    updated_at: datetime | None = Field(default=None, serialization_alias="updatedAt")


EmotionLabel = Literal["good", "focused", "joyful", "possibly_stressed", "possibly_low", "possibly_angry", "unknown"]


class EmotionCorrectionIn(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    capture_id: str = Field(alias="captureId", min_length=1, max_length=64)
    correction_id: str = Field(alias="correctionId", min_length=8, max_length=64)
    suggested_label: EmotionLabel = Field(alias="suggestedLabel")
    confirmed_label: EmotionLabel = Field(alias="confirmedLabel")
    # Images stay in the client. This contract deliberately accepts no image data.


class ExpressionFeatures(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    face_visible: bool = Field(alias="faceVisible")
    frontal: bool
    lighting_ok: bool = Field(alias="lightingOk")
    smile: float = Field(ge=0, le=1)
    brow_down: float = Field(alias="browDown", ge=0, le=1)
    frown: float = Field(ge=0, le=1)
    mouth_press: float = Field(alias="mouthPress", ge=0, le=1)
    look_down: float = Field(alias="lookDown", ge=0, le=1)
    eye_closed: float = Field(alias="eyeClosed", ge=0, le=1)


class ExpressionIn(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    expression_id: str = Field(alias="expressionId", min_length=8, max_length=64)
    session_id: str = Field(alias="sessionId")
    namespace: Literal["live", "demo"] = "live"
    source: Literal["webcam", "eufy_web_sdk", "simulated", "replay"] = "webcam"
    captured_at: datetime = Field(alias="capturedAt")
    camera_healthy: bool = Field(default=True, alias="cameraHealthy")
    quality: Literal["observable", "insufficient", "unavailable"]
    features: ExpressionFeatures

    @field_validator("captured_at")
    @classmethod
    def require_offset(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("capturedAt must include a timezone")
        return value


class ExpressionPublic(ApiModel):
    id: str
    session_id: str = Field(serialization_alias="sessionId")
    namespace: str
    captured_at: datetime = Field(serialization_alias="capturedAt")
    quality: str
    suggested_label: EmotionLabel = Field(serialization_alias="suggestedLabel")
    confidence: float
    stable_duration_ms: int = Field(serialization_alias="stableDurationMs")
    reason_codes: list[str] = Field(serialization_alias="reasonCodes")
    features: dict[str, Any]
    interpretation: Literal["visible_expression_hypothesis"] = "visible_expression_hypothesis"

    @field_validator("captured_at")
    @classmethod
    def restore_timezone(cls, value: datetime) -> datetime:
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


class LocalCaptureIn(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    capture_id: str = Field(alias="captureId", min_length=8, max_length=64)
    expression_id: str = Field(alias="expressionId", min_length=8, max_length=64)
    captured_at: datetime = Field(alias="capturedAt")

    @field_validator("captured_at")
    @classmethod
    def require_offset(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("capturedAt must include a timezone")
        return value


class ObservationIn(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    observation_key: str = Field(alias="observationKey", min_length=1, max_length=80)
    kind: Literal["action_started", "action_completed", "sustained_improvement", "recurrence", "expression_change", "unknown"]
    quality: Literal["observable", "insufficient", "unavailable"]
    observed_at: datetime = Field(alias="observedAt")
    evidence: dict[str, Any] = Field(default_factory=dict)

    @field_validator("observed_at")
    @classmethod
    def require_offset(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("observedAt must include a timezone")
        return value


class InterventionPublic(ApiModel):
    id: str
    session_id: str = Field(serialization_alias="sessionId")
    namespace: Literal["live", "demo"]
    category: str
    strategy: str
    status: str
    source: str
    context: dict[str, Any]
    decision: dict[str, Any]
    created_at: datetime = Field(serialization_alias="createdAt")
    displayed_at: datetime | None = Field(serialization_alias="displayedAt")
    due_at: datetime | None = Field(serialization_alias="dueAt")

    @field_validator("created_at", "displayed_at", "due_at")
    @classmethod
    def restore_sqlite_timezone(cls, value: datetime | None) -> datetime | None:
        return value.replace(tzinfo=timezone.utc) if value is not None and value.tzinfo is None else value


class VisionSampleIn(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    frame_id: str = Field(alias="frameId", min_length=1, max_length=70)
    captured_at: datetime = Field(alias="capturedAt")
    source: Literal["webcam", "eufy_web_sdk", "replay", "simulated"] = "webcam"
    camera_healthy: bool = Field(default=True, alias="cameraHealthy")
    quality: Literal["observable", "insufficient", "unavailable"]
    posture: Literal["normal", "bad", "unknown"] = "unknown"
    presence: Literal["present", "seated", "standing", "away", "unknown"]
    standing_confidence: float = Field(default=0, alias="standingConfidence", ge=0, le=1)

    @field_validator("captured_at")
    @classmethod
    def require_offset(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("capturedAt must include a timezone")
        return value


class DemoInterventionIn(ApiModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    session_id: str = Field(alias="sessionId")
    category: Literal["posture", "sedentary", "emotion"]
    learning: bool = False
    seed: int | None = None


class ObservationPublic(ApiModel):
    id: str
    observation_key: str = Field(serialization_alias="observationKey")
    kind: str
    quality: str
    observed_at: datetime = Field(serialization_alias="observedAt")
    evidence: dict[str, Any]

    @field_validator("observed_at")
    @classmethod
    def restore_sqlite_timezone(cls, value: datetime) -> datetime:
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


class UserPublic(ApiModel):
    id: str
    email: EmailStr
    display_name: str = Field(serialization_alias="displayName")
    timezone: str


class RegisterRequest(ApiModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    display_name: str = Field(min_length=1, max_length=80, alias="displayName")
    timezone: str = "Asia/Shanghai"


class LoginRequest(ApiModel):
    email: EmailStr
    password: str


class RefreshRequest(ApiModel):
    refresh_token: str = Field(alias="refreshToken")


class LogoutRequest(RefreshRequest):
    pass


class TokenPair(ApiModel):
    access_token: str = Field(serialization_alias="accessToken")
    refresh_token: str = Field(serialization_alias="refreshToken")
    token_type: str = Field(default="bearer", serialization_alias="tokenType")
    user: UserPublic


class DeviceCreate(ApiModel):
    name: str = Field(min_length=1, max_length=100)
    provider: str = "eufy"
    provider_device_ref: str | None = Field(default=None, alias="providerDeviceRef")
    platform: Literal["mock", "web", "android"] = "mock"


class DevicePublic(ApiModel):
    id: str
    name: str
    provider: str
    provider_device_ref: str | None = Field(serialization_alias="providerDeviceRef")
    platform: str
    created_at: datetime = Field(serialization_alias="createdAt")


class SessionCreate(ApiModel):
    device_id: str | None = Field(default=None, alias="deviceId")


class SessionPublic(ApiModel):
    id: str
    device_id: str | None = Field(serialization_alias="deviceId")
    started_at: datetime = Field(serialization_alias="startedAt")
    ended_at: datetime | None = Field(serialization_alias="endedAt")
    status: str


class EventSource(ApiModel):
    kind: str
    provider: str = "lookbuddy"
    sdk: str | None = None
    sdk_version: str | None = Field(default=None, alias="sdkVersion")
    raw_type: str | None = Field(default=None, alias="rawType")


class Evidence(ApiModel):
    method: str
    media_ref: str | None = Field(default=None, alias="mediaRef")


class Privacy(ApiModel):
    contains_image: bool = Field(default=False, alias="containsImage")
    retention: str = "event_only"


class DomainEventIn(ApiModel):
    schema_name: Literal["lookbuddy.event"] = Field(default="lookbuddy.event", alias="schema")
    version: Literal[1] = 1
    event_id: str = Field(alias="eventId", min_length=8, max_length=64)
    type: str = Field(pattern=r"^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$", max_length=80)
    device_id: str | None = Field(default=None, alias="deviceId")
    session_id: str | None = Field(default=None, alias="sessionId")
    seq: int | None = Field(default=None, ge=0)
    occurred_at: datetime = Field(alias="occurredAt")
    source: EventSource
    confidence: float | None = Field(default=None, ge=0, le=1)
    duration_ms: int | None = Field(default=None, alias="durationMs", ge=0)
    payload: dict[str, Any] = Field(default_factory=dict)
    evidence: Evidence | None = None
    privacy: Privacy = Field(default_factory=Privacy)

    @field_validator("occurred_at")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("occurredAt must include a timezone")
        return value


class EventBatchRequest(ApiModel):
    events: list[DomainEventIn] = Field(min_length=1, max_length=100)


class EventBatchResponse(ApiModel):
    accepted: int
    duplicates: int


class StateSnapshotIn(ApiModel):
    session_id: str = Field(alias="sessionId")
    captured_at: datetime = Field(alias="capturedAt")
    presence: Literal["online", "away", "offline"]
    sedentary_ms: int = Field(default=0, alias="sedentaryMs", ge=0)
    bad_posture_ratio_5m: float = Field(default=0, alias="badPostureRatio5m", ge=0, le=1)
    bad_posture_streak_ms: int = Field(default=0, alias="badPostureStreakMs", ge=0)
    last_drink_at: datetime | None = Field(default=None, alias="lastDrinkAt")
    fatigue_signals_10m: int = Field(default=0, alias="fatigueSignals10m", ge=0)


class ReminderPublic(ApiModel):
    id: str
    session_id: str = Field(serialization_alias="sessionId")
    category: str
    priority: int
    status: str
    message: str
    reason_codes: list[str] = Field(serialization_alias="reasonCodes")
    created_at: datetime = Field(serialization_alias="createdAt")
    expires_at: datetime = Field(serialization_alias="expiresAt")


    @computed_field(alias="chatEntry")
    @property
    def chat_entry(self) -> dict[str, str] | None:
        if self.status == "silent":
            return None
        label = next((code for code in self.reason_codes if chat_entry("emotion", code)), None)
        return chat_entry(self.category, label)


class SnapshotResponse(ApiModel):
    reminder: ReminderPublic | None = None
    intervention: InterventionPublic | None = None


class ReminderRespondRequest(ApiModel):
    action: Literal["accepted", "snoozed", "ignored"]
    snooze_minutes: int | None = Field(default=None, alias="snoozeMinutes", ge=1, le=120)


class DiaryCandidate(ApiModel):
    kind: str
    title: str
    facts: dict[str, Any]
    score: int = Field(ge=0, le=100)
    reason_codes: list[str] = Field(alias="reasonCodes")


class DiaryPublic(ApiModel):
    id: str
    diary_date: date = Field(serialization_alias="date")
    status: str
    candidates: list[DiaryCandidate]
    content: str
    generated_by: str = Field(serialization_alias="generatedBy")
    updated_at: datetime = Field(serialization_alias="updatedAt")


class DiaryUpdate(ApiModel):
    content: str = Field(min_length=1, max_length=10000)

