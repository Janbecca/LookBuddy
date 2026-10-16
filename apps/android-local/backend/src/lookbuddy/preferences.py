from sqlalchemy.orm import Session

from .models import UserPreferences
from .schemas import PreferencesPublic


def preferences_for(db: Session, user_id: str) -> PreferencesPublic:
    stored = db.get(UserPreferences, user_id)
    return PreferencesPublic.model_validate(stored) if stored else PreferencesPublic()
