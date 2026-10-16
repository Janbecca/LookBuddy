"""One demonstration username across Web, Android, SDK and desktop clients."""
import secrets
import unicodedata

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import ForeignKey, String, select
from sqlalchemy.orm import Mapped, Session, mapped_column

from .config import get_settings
from .database import Base, get_db
from .display_budget import lock_user
from .models import User
from .schemas import TokenPair
from .security import hash_password


class AccountName(Base):
    __tablename__ = "account_names"
    name: Mapped[str] = mapped_column(String(320), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), unique=True)


def normalize_username(value):
    value = unicodedata.normalize("NFKC", value).strip().casefold()
    if not value or len(value) > 320 or any(unicodedata.category(c).startswith("C") for c in value):
        raise ValueError("Username must contain 1-320 printable characters")
    return value


def username_for(db, user_id):
    return db.scalar(select(AccountName.name).where(AccountName.user_id == user_id))


def ensure_email_name(db, user):
    # Legacy generated demo e-mails are placeholders, not usernames.
    if user.email.startswith(("demo-", "username-")) and user.email.endswith("@example.com"):
        return
    name = normalize_username(user.email)
    if db.get(AccountName, name) is None and username_for(db, user.id) is None:
        db.add(AccountName(name=name, user_id=user.id))
        db.flush()


def backfill_email_names(db):
    # Retain real e-mail as a valid username without moving any historical rows.
    for user in db.scalars(select(User).where(User.is_active.is_(True)).order_by(User.created_at, User.id)):
        ensure_email_name(db, user)


class UsernameLogin(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str
    password: str = Field(default="", repr=False)
    displayName: str = Field(default="体验用户", min_length=1, max_length=80)

    @field_validator("username")
    @classmethod
    def valid_name(cls, value):
        return normalize_username(value)


router = APIRouter(prefix="/v1")


@router.post("/auth/username", response_model=TokenPair)
def username_login(body: UsernameLogin, db: Session = Depends(get_db)):
    settings = get_settings()
    if not settings.demo_login_enabled:
        raise HTTPException(403, "Username-only demonstration login is disabled")
    lock_user(db, "username-create")
    if db.get_bind().dialect.name == "postgresql":
        # One transaction-scoped lock also serializes the first account creation.
        from sqlalchemy import text
        db.execute(text("SELECT pg_advisory_xact_lock(638210164701)"))
    backfill_email_names(db)
    identity = db.get(AccountName, body.username)
    if identity is None:
        user = User(email="username-" + secrets.token_hex(24) + "@example.com",
                    # Existing schema requires a hash. Never hash/save supplied password.
                    password_hash=hash_password(secrets.token_urlsafe(48)), display_name=body.displayName)
        db.add(user)
        db.flush()
        db.add(AccountName(name=body.username, user_id=user.id))
        db.flush()
    else:
        user = db.get(User, identity.user_id)
        if user is None or not user.is_active:
            raise HTTPException(401, "Account unavailable")
    from .api import issue_tokens
    return issue_tokens(db, user)
