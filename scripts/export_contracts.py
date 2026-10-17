"""Mechanically export versioned shared schemas from backend API definitions."""
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "apps/backend/src"))

from lookbuddy.schemas import (
    DomainEventIn, EmotionCorrectionIn, InterventionPublic, ObservationIn, DisplayClaimIn,
    PreferencesPatch, PreferencesPublic, ReminderPublic, ReminderRespondRequest, StateSnapshotIn,
    VisionSampleIn, DemoInterventionIn, ObservationPublic, SnapshotResponse,
    ExpressionIn, ExpressionPublic, ExpressionFeatures, LocalCaptureIn,
)

from lookbuddy.product import DemoPhoneIn, ProfilePatch, ScheduleIn, OwnerIn, MoodIn, MarkIn, GreetingIn
from lookbuddy.accounts import UsernameLogin
from lookbuddy.schemas import UserPublic

target = root / "packages/contracts/schemas"
target.mkdir(parents=True, exist_ok=True)
for model in (
    DomainEventIn, EmotionCorrectionIn, InterventionPublic, ObservationIn, DisplayClaimIn,
    PreferencesPatch, PreferencesPublic, ReminderPublic, ReminderRespondRequest, StateSnapshotIn,
    VisionSampleIn, DemoInterventionIn, ObservationPublic, SnapshotResponse,
    ExpressionIn, ExpressionPublic, ExpressionFeatures, LocalCaptureIn,
    DemoPhoneIn, ProfilePatch, ScheduleIn, OwnerIn, MoodIn, MarkIn, GreetingIn,
    UsernameLogin, UserPublic,
):
    mode = "serialization" if model.__name__.endswith("Public") else "validation"
    schema = model.model_json_schema(by_alias=True, mode=mode)
    schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    schema["$id"] = f"urn:lookbuddy:contract:v1:{model.__name__}"
    (target / f"{model.__name__}.v1.json").write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Exported contracts to {target}")
